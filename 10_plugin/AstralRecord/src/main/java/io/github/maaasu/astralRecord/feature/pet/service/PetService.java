package io.github.maaasu.astralRecord.feature.pet.service;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.repository.PetRepository;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService.RuntimeAccountAuthority;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.inventory.service.*;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryOperationSnapshotParser;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryOperationSnapshot;
import io.github.maaasu.astralRecord.infrastructure.logging.*;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.*;

/**
 * API個体キャッシュと非同期保存を管理します。戦闘中はキャッシュだけを更新します。
 * 素材を伴う操作は既存account保存lane・支払い予約・三者マージを使用します。
 */
public final class PetService implements PetRuntimeService.StateBridge {
    private final Plugin plugin;
    private final Executor executor;
    private final io.github.maaasu.astralRecord.feature.item.service.ItemService items;
    private final io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory factory;
    private final Map<UUID,CompletableFuture<Void>> loading=new ConcurrentHashMap<>();
    private final PetRepository repository = new PetRepository();
    private final InventoryService inventory;
    private final InventorySaveCoordinator saves;
    private final Map<UUID, AccountState> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, ExternalOperation> unresolved = new ConcurrentHashMap<>();
    private final Set<UUID> adminGrowthPending = ConcurrentHashMap.newKeySet();
    private final Map<UUID,LegacyEquipAttempt> legacyEquipAttempts=new ConcurrentHashMap<>();
    private volatile PetMaster master = new PetMaster(new JsonObject());
    private volatile Consumer<AstPlayer> refreshListener = ignored -> { };
    private volatile Consumer<AstPlayer> progressListener = ignored -> { };
    private volatile Consumer<AstPlayer> pauseListener = ignored -> { };
    private volatile Consumer<AstPlayer> hatchSuccessListener = ignored -> { };
    private volatile java.util.function.Function<UUID, RuntimeAccountAuthority> progressAuthorityProvider = ignored -> null;
    private volatile java.util.function.Predicate<AstPlayer> activityEligible=ignored->false;
    private volatile boolean stopped;

    /** 非同期通信のexecutorと既存保存laneを接続します。構築時の通信は行いません。 */
    public PetService(Plugin plugin, Executor executor, InventoryService inventory, InventorySaveCoordinator saves,
        io.github.maaasu.astralRecord.feature.item.service.ItemService items,
        io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory factory) {
        this.plugin=plugin;this.executor=executor;this.inventory=inventory;this.saves=saves;this.items=items;this.factory=factory;
    }
    /**
     * 個体の公開情報を通常アイテムのtooltipとして描画します。通信は行いません。
     * @param pet 所有確認済みのペット個体
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public org.bukkit.inventory.ItemStack itemStack(PetInstance pet){return io.github.maaasu.astralRecord.feature.pet.view.PetItemView.create(pet,this,items,factory);}
    /**
     * 読込用マスタースナップショットを準備します。API通信を伴うため非同期専用です。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public PetMaster loadMasterSnapshot(){return new PetMaster(repository.master());}
    /**
     * 全マスター公開段階で準備済みスナップショットを適用します。
     * @param snapshot 契約に従った入力値
     */
    public void publishMasterSnapshot(PetMaster snapshot){master=snapshot;}

    /** 通常進行保存と退出時の最終保存に、既存 account session の証拠を固定する。 */
    public void setProgressAuthorityProvider(java.util.function.Function<UUID, RuntimeAccountAuthority> provider) {
        progressAuthorityProvider = java.util.Objects.requireNonNull(provider);
    }
    /**
     * 移管で未ロードになった個体を非同期取得します。同accountの重複要求を抑止します。
     * @param accountId 所有アカウントID
     */
    public void ensureLoaded(UUID accountId){
        loading.computeIfAbsent(accountId,id->{
            AccountState state=accounts.computeIfAbsent(id,AccountState::new);
            CompletableFuture<Void> future;
            synchronized(state){
                future=state.tail.handle((ignored,failure)->null).thenRunAsync(()->{
                    if(unresolved.containsKey(id))return;
                    loadAccount(id);
                },executor);
                state.tail=future;
            }
            return future.whenComplete((ignored,failure)->{
                loading.remove(id);
                if(failure!=null){log(failure);return;}
                if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->{
                    for(AstPlayer player:io.github.maaasu.astralRecord.feature.player.AstPlayerCache.getAll()) {
                        if(player.getAccount().getUuid().equals(id)) {
                            if(hasHatchedPet(id))hatchSuccessListener.accept(player);
                            refreshListener.accept(player);
                        }
                    }
                });
            });
        });
    }
    /**
     * APIから新しいマスターを準備して一括公開します。非同期ロード段階専用です。
     */
    public void reloadMaster() { master=new PetMaster(repository.master()); }
    /**
     * 公開済みのペットマスターを返します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public PetMaster master() { return master; }
    /**
     * 読み込み済みアカウントに孵化済みペットがいるか確認します。魔法の卵は含みません。
     *
     * @param accountId 対象アカウント ID
     * @return 孵化済みペットがいれば true
     */
    public boolean hasHatchedPet(@NotNull UUID accountId) {
        AccountState state = accounts.get(accountId);
        if (state == null) return false;
        synchronized (state) {
            return state.pets.values().stream().anyMatch(pet -> !pet.isEgg());
        }
    }
    /**
     * 孵化の確定後に呼ぶガイド記録処理を登録します。
     *
     * @param listener 孵化したペットの所有者を受け取るリスナー
     */
    public void setHatchSuccessListener(@NotNull Consumer<AstPlayer> listener) {
        hatchSuccessListener = Objects.requireNonNull(listener);
    }
    /**
     * 召喚状態の再描画通知先を設定します。通知はメインスレッドで実行します。
     * @param listener 呼出threadの契約に従う通知先
     */
    public void setRefreshListener(Consumer<AstPlayer> listener) { refreshListener=listener; }
    /** @param listener 進行保存後の表示更新先。全インベントリの取り込みは行わずメインスレッドで呼びます */
    public void setProgressListener(Consumer<AstPlayer> listener) { progressListener=listener; }
    /**
     * 原子操作前に、メインスレッドで旧ペットの最終状態を確定して退場させる通知先です。
     * @param listener 呼出threadの契約に従う通知先
     */
    public void setPauseListener(Consumer<AstPlayer> listener){pauseListener=listener;}
    /**
     * 実際に召喚して活動できる状態の判定を接続します。メインスレッド専用です。
     * @param predicate 主人が生存し、有効な召喚個体が存在する判定
     */
    public void setActivityEligibility(java.util.function.Predicate<AstPlayer> predicate){activityEligible=predicate;}

    /**
     * ログイン読込段階でアカウント個体を取得します。Bukkitメインスレッドでは使用禁止です。
     * @param accountId 所有アカウントID
     */
    public void loadAccount(UUID accountId) { publishAccount(accountId,repository.account(accountId)); }
    private void publishAccount(UUID accountId,JsonObject response) {
        AccountState state=accounts.computeIfAbsent(accountId,AccountState::new);
        synchronized(state) {
            Map<UUID,PetInstance> latest=new LinkedHashMap<>();
            for(JsonElement row:array(response,"instances")) {
                PetInstance pet=new PetInstance(row.getAsJsonObject());
                if(!pet.accountId().equals(accountId)) throw new IllegalStateException("Pet owner mismatch");
                latest.put(pet.id(),pet);
            }
            state.progress.entrySet().removeIf(row -> !latest.containsKey(row.getKey())
                || row.getValue().revision == row.getValue().acknowledged && row.getValue().request == null);
            state.pets=latest;
            state.generation++;
            String selected=text(response,"equippedPetId","");
            state.equipped=selected.isBlank()?null:UUID.fromString(selected);
        }
    }
    /**
     * 所有個体一覧の固定コピーを返します。HTTP通信は行いません。
     * @param accountId 所有アカウントID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public List<PetInstance> instances(UUID accountId) {
        AccountState state=accounts.get(accountId);if(state==null)return List.of();
        synchronized(state){return List.copyOf(state.pets.values());}
    }
    /**
     * 所有者を照合してキャッシュ個体を返します。未ロード・移管済みならnullです。
     * @param accountId 所有アカウントID
     * @param instanceId 対象個体ID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public @Nullable PetInstance find(UUID accountId,UUID instanceId) {
        AccountState state=accounts.get(accountId);if(state==null)return null;
        synchronized(state){return state.pets.get(instanceId);}
    }
    /**
     * 装備選択IDを返します。
     * @param accountId 所有アカウントID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public @Nullable UUID equippedId(UUID accountId) {
        AccountState state=accounts.get(accountId);if(state==null)return null;
        synchronized(state){return state.equipped;}
    }
    /**
     * 表示上のHP比率を返します。保存待ちの状態を優先します。
     * @param pet 所有確認済みのペット個体
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public double healthRatio(PetInstance pet) {
        AccountState state=accounts.get(pet.accountId());if(state==null)return number(pet.details(),"healthRatio",1);
        synchronized(state){Progress progress=state.progress.get(pet.id());return progress==null?number(pet.details(),"healthRatio",1):progress.health;}
    }
    /**
     * 保存待ちを含む戦闘不能状態を返します。
     * @param pet 所有確認済みのペット個体
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean dead(PetInstance pet) {
        AccountState state=accounts.get(pet.accountId());if(state==null)return pet.dead();
        synchronized(state){Progress progress=state.progress.get(pet.id());return progress==null?pet.dead():progress.dead;}
    }
    @Override public PetRuntimeService.@Nullable RuntimePet equipped(AstPlayer owner) {
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId))return null;
        UUID selected=equippedId(accountId);
        PetInstance pet=selected==null?null:find(accountId,selected);
        if(pet==null||pet.isEgg())return null;
        if(!inventory.isPetInEquipmentSlot(accountId,selected)){
            if(org.bukkit.Bukkit.isPrimaryThread()&&AccountModeGuard.isGameplayPlayer(owner)
                &&AstPlayerCache.get(owner.getBukkit())==owner&&inventory.isPetInGameBag(accountId,selected)){
                long generation=generation(accountId),now=System.nanoTime();
                LegacyEquipAttempt previous=legacyEquipAttempts.get(accountId);
                if(previous==null||!previous.petId().equals(selected)||previous.generation()!=generation
                    ||now-previous.retryAfterNanos()>=0){
                    legacyEquipAttempts.put(accountId,new LegacyEquipAttempt(selected,generation,now+TimeUnit.SECONDS.toNanos(5)));
                    try{equip(owner,selected).whenComplete((ignored,failure)->{if(failure!=null)log(failure);});}
                    catch(RuntimeException failure){log(failure);}
                }
            }
            return null;
        }
        JsonObject species=master.species(pet.speciesId());if(species.isEmpty())return null;
        JsonObject details=pet.details(),stats=object(details,"stats"),definitions=object(species,"stats");
        List<PetRuntimeService.PetSkill> skills=new ArrayList<>();
        for(JsonElement learned:array(details,"skills")) {
            String skillId=text(learned.getAsJsonObject(),"id","");JsonObject skill=master.skill(pet.speciesId(),skillId);
            if(skill.isEmpty())continue;
            skills.add(new PetRuntimeService.PetSkill(skillId,text(skill,"name","ペットスキル"),
                PetRuntimeService.SkillTrigger.valueOf(text(skill,"trigger",PetRuntimeService.SkillTrigger.PERIODIC.name())),
                PetRuntimeService.SkillEffect.valueOf(text(skill,"effect",PetRuntimeService.SkillEffect.FOLLOW_UP.name())),
                number(skill,"value",0),number(skill,"chance",1),(long)(number(skill,"cooldownSeconds",10)*1000),
                (long)(number(skill,"durationSeconds",0)*1000),(int)number(skill,"hitCount",1),(int)number(skill,"attackCount",1)));
        }
        Map<String,Long> cooldowns=new HashMap<>();
        for (var row : object(details,"cooldowns").entrySet()) cooldowns.put(row.getKey(),row.getValue().getAsLong());
        AccountState state=accounts.get(accountId);
        synchronized(state){Progress progress=state.progress.get(pet.id());if(progress!=null)cooldowns=new HashMap<>(progress.cooldowns);}
        return new PetRuntimeService.RuntimePet(pet.id(),pet.name(),EntityType.valueOf(text(species,"entityType",EntityType.WOLF.name())),
            PetGrowthCalculator.effectiveSize(master,number(details,"size",1),(int)number(details,"level",1)),healthRatio(pet),dead(pet),
            new PetRuntimeService.PetCoefficients(coefficient(stats,definitions,"VITALITY"),coefficient(stats,definitions,"POWER"),
                coefficient(stats,definitions,"DEFENSE"),coefficient(stats,definitions,"EVASION"),coefficient(stats,definitions,"SUPPORT")),
            List.copyOf(skills),Map.copyOf(cooldowns),basicAttack(species),(int)number(details,"level",1),
            text(object(details,"appearance"),"collarColor",""),text(object(details,"appearance"),"catType",""));
    }
    private static PetRuntimeService.BasicAttack basicAttack(JsonObject species){
        JsonObject basic=object(species,"basicAttack");
        return basic.isEmpty()?null:new PetRuntimeService.BasicAttack(number(basic,"damageRatio",0),
            (long)(number(basic,"cooldownSeconds",3)*1000),number(basic,"range",3));
    }
    /**
     * インベントリの所有者指定がない表示経路向けに、ロード済み個体だけを参照します。
     * @param instanceId 対象個体ID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public PetInstance findLoaded(UUID instanceId){
        for(AccountState state:accounts.values()){synchronized(state){PetInstance pet=state.pets.get(instanceId);if(pet!=null)return pet;}}
        return null;
    }
    /**
     * 結果不明の原子操作が残っているかを返します。
     * @param accountId 所有アカウントID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean hasUnresolved(UUID accountId){return unresolved.containsKey(accountId);}

    private static double coefficient(JsonObject stats,JsonObject definitions,String id) {
        JsonObject definition=object(definitions,id);double value=number(object(stats,id),"currentValue",0);
        double min=number(definition,"inheritanceMin",0),max=number(definition,"inheritanceMax",min),scale=number(definition,"scale",20);
        return min+(max-min)*Math.max(0,value)/(Math.max(0.001,scale)+Math.max(0,value));
    }
    @Override public void updateRuntime(AstPlayer owner,UUID id,double health,boolean dead,Map<String,Long> cooldowns) {
        AccountState state=accounts.get(owner.getAccount().getUuid());if(state==null)return;
        synchronized(state){
            PetInstance pet=state.pets.get(id);if(pet==null||pet.isEgg())return;
            Progress progress=state.progress.computeIfAbsent(id,ignored->new Progress(pet));
            double bounded=Math.max(0,Math.min(1,health));
            if(progress.health==bounded&&progress.dead==dead&&progress.cooldowns.equals(cooldowns))return;
            progress.health=bounded;progress.dead=dead;progress.cooldowns=Map.copyOf(cooldowns);progress.revision++;
        }
    }
    /**
     * 討伐対象レベルから独自EXPを算出し、活動可能な装備ペットへ蓄積します。
     * @param owner AFKを除外済みの報酬受取人
     * @param targetLevel 討伐された敵の実効レベル
     * @throws ArithmeticException 独自EXPがlongの範囲を超える場合
     */
    @Override public void grantCombatExperience(AstPlayer owner,int targetLevel) {
        grantExperience(owner,PetGrowthCalculator.combatExperience(master,targetLevel));
    }
    /**
     * 採集対象レベルから独自EXPを算出し、活動可能な装備ペットへ蓄積します。
     * @param owner AFKを除外済みの採集報酬受取人
     * @param targetLevel 破壊された採集オブジェクトのレベル
     * @throws ArithmeticException 独自EXPがlongの範囲を超える場合
     */
    public void grantGatheringExperience(AstPlayer owner,int targetLevel) {
        grantExperience(owner,PetGrowthCalculator.gatheringExperience(master,targetLevel));
    }
    private void grantExperience(AstPlayer owner,long amount) {
        if(amount<=0||!activityEligible.test(owner))return;
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId)||adminGrowthPending.contains(accountId))return;
        AccountState state=accounts.get(accountId);if(state==null)return;
        synchronized(state){
            PetInstance pet=state.equipped==null?null:state.pets.get(state.equipped);
            if(pet==null||pet.isEgg()||dead(pet))return;
            Progress progress=state.progress.computeIfAbsent(pet.id(),ignored->new Progress(pet));
            progress.experience=Math.addExact(progress.experience,amount);progress.revision++;
        }
    }
    /**
     * 管理者指定の成長度まで、装備中ペットの経験値を既存の進行保存経路で付与します。
     * 保存待ちを先に確定し、APIの成長処理で指定成長度を保存します。
     * @param owner 対象ペットを装備した、読み込み済みの通常プレイアカウント
     * @param targetLevel 現在より大きく、マスター上限以下の目標成長度
     * @return API保存後に確定した成長度。失敗時は例外で完了します
     * @throws IllegalArgumentException 対象個体または目標成長度が不正な場合
     * @throws IllegalStateException 操作中、未解決、またはメインスレッド以外から呼ばれた場合
     */
    public CompletableFuture<Integer> raiseEquippedLevel(AstPlayer owner,int targetLevel) {
        if(!org.bukkit.Bukkit.isPrimaryThread())throw new IllegalStateException("Pet admin growth must begin on Bukkit main thread");
        UUID accountId=owner.getAccount().getUuid();
        AccountState state=accounts.get(accountId);
        if(state==null||unresolved.containsKey(accountId)||!adminGrowthPending.add(accountId))
            throw new IllegalStateException("Pet account is unavailable");
        try {
            synchronized(state) {
                PetInstance pet=state.equipped==null?null:state.pets.get(state.equipped);
                int maxLevel=(int)number(master.rules(),"maxLevel",100);
                int current=pet==null?0:(int)number(pet.details(),"level",0);
                if(pet==null||pet.isEgg()||dead(pet)||targetLevel<=current||targetLevel>maxLevel)
                    throw new IllegalArgumentException("Invalid pet or target level");
                pauseListener.accept(owner);
                UUID petId=pet.id();
                AtomicBoolean growthSubmitted=new AtomicBoolean();
                CompletableFuture<Integer> result=state.tail.handle((ignored,failure)->null).thenApplyAsync(ignored->{
                    flushBlocking(state);
                    if(unresolved.containsKey(accountId))throw new IllegalStateException("Pet operation unresolved");
                    synchronized(state) {
                        PetInstance latest=state.pets.get(petId);
                        if(latest==null||latest.isEgg()||state.equipped==null||!state.equipped.equals(petId)||dead(latest))
                            throw new IllegalStateException("Pet changed during admin growth");
                        int level=(int)number(latest.details(),"level",0);
                        if(level>=targetLevel)return level;
                        Progress progress=state.progress.computeIfAbsent(petId,unused->new Progress(latest));
                        progress.revision++;
                        JsonObject body=new JsonObject();
                        body.addProperty("operationId",UUID.randomUUID().toString());
                        body.addProperty("updatedBy",accountId.toString());
                        body.addProperty("expectedVersion",latest.version());
                        body.addProperty("experience",0);
                        body.addProperty("adminTargetLevel",targetLevel);
                        body.addProperty("healthRatio",progress.health);
                        body.addProperty("isDead",progress.dead);
                        body.add("cooldowns",new Gson().toJsonTree(progress.cooldowns));
                        RuntimeAccountAuthority authority=progressAuthorityProvider.apply(accountId);
                        if(authority!=null)authority.writeTo(body);
                        progress.request=new ProgressRequest(body,0,progress.revision);
                        growthSubmitted.set(true);
                    }
                    try { flushBlocking(state); }
                    catch(PetRepository.RejectedOperation rejected) {
                        synchronized(state) {
                            Progress progress=state.progress.get(petId);
                            if(progress!=null&&progress.request!=null&&progress.request.body.has("adminTargetLevel"))
                                state.progress.remove(petId);
                        }
                        growthSubmitted.set(false);
                        throw rejected;
                    }
                    synchronized(state){return (int)number(state.pets.get(petId).details(),"level",0);}
                },executor);
                state.tail=result.thenApply(ignored->null);
                CompletableFuture<Integer> reported=result.handle((level,failure)->{
                    if(failure==null)return level;
                    if(growthSubmitted.get())throw new CompletionException(new AdminGrowthPendingException());
                    throw new CompletionException(failure);
                });
                return reported.whenComplete((ignored,failure)->{
                    adminGrowthPending.remove(accountId);
                    if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->{
                        if(accounts.get(accountId)==state&&AstPlayerCache.get(owner.getBukkit())==owner)
                            progressListener.accept(owner);
                    });
                });
            }
        } catch(RuntimeException failure) {
            adminGrowthPending.remove(accountId);
            throw failure;
        }
    }
    /** 管理者による成長EXPが保存待ちで、同じ操作IDの再試行対象であることを示します。 */
    public static final class AdminGrowthPendingException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID=1L;
    }
    /**
     * 所有済み報酬個体を照合してcacheへ追加します。HTTPを呼ぶため非同期専用です。
     * 既存個体の進行やアカウント世代は置き換えません。
     * @param accountId 受取アカウントID
     * @param instanceId ペット個体ID
     * @param model 受取アイテム定義
     * @return 所有者・アイテム・卵種別が一致し、accountがロード済みならtrue
     */
    public boolean preloadOwnedItem(UUID accountId, UUID instanceId,
        ItemModel model) {
        var category = ItemCategory.fromApiValue(model.getCategory());
        if (category != ItemCategory.PET
            && category != ItemCategory.PET_EGG) return false;
        PetInstance pet = new PetInstance(repository.instance(accountId, instanceId));
        if (!pet.id().equals(instanceId) || !pet.accountId().equals(accountId)
            || !pet.itemId().equalsIgnoreCase(model.getId())
            || pet.isEgg() != (category == ItemCategory.PET_EGG)) return false;
        AccountState state = accounts.get(accountId);
        if (state == null) return false;
        synchronized (state) {
            if (accounts.get(accountId) != state) return false;
            state.pets.compute(instanceId, (id, cached) -> cached == null || cached.version() < pet.version() ? pet : cached);
            return true;
        }
    }

    /** 卵・孵化済みペット付与の停止理由。通信結果不明は既存操作IDで復旧を継続します。 */
    public enum EggGrantFailure { NONE, FULL, FAILED, PENDING }
    /** API確定済みの付与数と停止理由です。部分付与も確定数だけを通知します。 */
    public record EggGrantResult(int grantedAmount, EggGrantFailure failure) { }

    /**
     * 管理者の卵アイテム付与を既存の原子API操作へ接続します。メインスレッド専用です。
     * speciesはマスターのeggItemIdから解決し、1個ずつ容量を確認して異なる個体を作成します。
     * HTTPと保存は非同期で、結果通知はメインスレッドへ戻します。
     * @param owner 追加先のロード済みプレイヤー
     * @param model 卵のアイテム定義
     * @param amount 要求数。1未満は1へ補正
     * @return API確定数と停止理由。成功前に通常stackを生成しません
     */
    public CompletableFuture<EggGrantResult> grantEggItems(AstPlayer owner,
        ItemModel model, int amount) {
        return grantPetItems(owner, model, amount, true);
    }

    /**
     * 管理権限を検証済みの取得経路から、野生卵を通常孵化した直後と同じ個体を付与します。
     * メインスレッド専用です。孵化素材は消費せず、BAG予約・非同期保存・結果不明時の復旧を維持します。
     * @param owner 追加先のロード済みプレイヤー。取得実行者とは異なる場合があります
     * @param model 孵化済みペットのアイテム定義
     * @param amount 要求数。1未満は1へ補正
     * @return API確定数と停止理由。結果通知はメインスレッドへ戻します
     */
    public CompletableFuture<EggGrantResult> grantAdminPetItems(AstPlayer owner,
        ItemModel model, int amount) {
        return grantPetItems(owner, model, amount, false);
    }

    /** 卵・孵化済みアイテムの種類を対応するマスター参照から一意に解決します。 */
    private CompletableFuture<EggGrantResult> grantPetItems(AstPlayer owner,
        ItemModel model, int amount, boolean egg) {
        List<String> species = master.speciesIds().stream()
            .filter(id -> PetMaster.itemId(text(master.species(id), egg ? "eggItemId" : "petItemId", "")).equalsIgnoreCase(model.getId()))
            .toList();
        if (ItemCategory.fromApiValue(model.getCategory())
                != (egg ? ItemCategory.PET_EGG : ItemCategory.PET) || species.size() != 1)
            return CompletableFuture.completedFuture(new EggGrantResult(0, EggGrantFailure.FAILED));
        CompletableFuture<EggGrantResult> completion = new CompletableFuture<>();
        grantNextPetItem(owner, model, species.getFirst(), egg, Math.max(1, amount), 0, completion);
        return completion;
    }

    /** 各API確定後にメインスレッドで次の1個を開始し、未確定操作の重複生成を防ぎます。 */
    private void grantNextPetItem(AstPlayer owner, ItemModel model,
        String speciesId, boolean egg, int amount, int granted, CompletableFuture<EggGrantResult> completion) {
        if (granted == amount) {
            completion.complete(new EggGrantResult(granted, EggGrantFailure.NONE));
            return;
        }
        if (!plugin.isEnabled() || !owner.getBukkit().isOnline()
            || io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(owner.getBukkit()) != owner) {
            completion.complete(new EggGrantResult(granted, EggGrantFailure.FAILED));
            return;
        }
        UUID accountId = owner.getAccount().getUuid();
        if (hasUnresolved(accountId)) {
            completion.complete(new EggGrantResult(granted, EggGrantFailure.PENDING));
            return;
        }
        var reserved = inventory.reserveBagSlotForPreparedInstance(owner, model);
        var reservation = reserved.reservation();
        if (reservation == null) {
            completion.complete(new EggGrantResult(granted,
                reserved.remainingBagSlots() < 0 ? EggGrantFailure.FAILED : EggGrantFailure.FULL));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("speciesId", speciesId);
        CompletableFuture<JsonObject> operation;
        try {
            operation = mutate(owner, UUID.randomUUID(), egg ? "/eggs" : "/admin-grants", "POST", body, Map.of(), reservation);
        } catch (RuntimeException failure) {
            inventory.releasePreparedInstanceReservation(reservation);
            log(failure);
            completion.complete(new EggGrantResult(granted, EggGrantFailure.FAILED));
            return;
        }
        operation.whenComplete((result, failure) -> {
            if (!plugin.isEnabled()) {
                completion.complete(new EggGrantResult(granted, EggGrantFailure.PENDING));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure != null || rejected(result)) {
                    completion.complete(new EggGrantResult(granted,
                        hasUnresolved(accountId) ? EggGrantFailure.PENDING : EggGrantFailure.FAILED));
                    return;
                }
                if (owner.getBukkit().isOnline()
                    && io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(owner.getBukkit()) == owner)
                    inventory.refreshNormalInventoryGrantUi(owner);
                grantNextPetItem(owner, model, speciesId, egg, amount, granted + 1, completion);
            });
        });
    }

    @Override public List<String> eggSpecies(){return master.speciesIds();}
    @Override public double eggDropChance(){return number(master.rules(),"eggDropChance",0);}
    @Override public double threatMultiplier(){return number(master.rules(),"threatMultiplier",0.5D);}
    @Override public double autonomousAttackRange(){return number(master.rules(),"autonomousAttackRange",8.0D);}
    @Override public double eggWeight(String speciesId){return Math.max(0,number(master.species(speciesId),"dropWeight",0));}
    @Override public void awardEgg(AstPlayer owner,String speciesId,UUID rewardId) {
        JsonObject body=new JsonObject();body.addProperty("speciesId",speciesId);
        mutate(owner,rewardId,"/eggs","POST",body,Map.of()).exceptionally(failure->{log(failure);return null;});
    }
    /**
     * 非同期で進行・HP・死亡・クールダウンを保存します。同accountの通信は直列化します。
     * @param accountId 所有アカウントID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public CompletableFuture<Void> flush(UUID accountId) {
        AccountState state=accounts.get(accountId);if(state==null)return CompletableFuture.completedFuture(null);
        synchronized(state){
            state.tail=state.tail.handle((ignored,failure)->null).thenRunAsync(()->flushBlocking(state),executor);
            return state.tail;
        }
    }
    private void flushBlocking(AccountState state) {
        if(accounts.get(state.id)!=state)return;
        ExternalOperation external=unresolved.get(state.id);
        if(external!=null && external.baseline!=null)return;
        List<UUID> ids; synchronized(state){ids=List.copyOf(state.progress.keySet());}
        boolean updated=false;
        for(UUID id:ids) {
            Progress progress;ProgressRequest request;
            synchronized(state){
                progress=state.progress.get(id);if(progress==null||progress.revision==progress.acknowledged)continue;
                PetInstance pet=state.pets.get(id);if(pet==null)continue;
                if(progress.request==null){
                    JsonObject body=new JsonObject();body.addProperty("operationId",UUID.randomUUID().toString());
                    body.addProperty("updatedBy",state.id.toString());body.addProperty("expectedVersion",pet.version());
                     body.addProperty("experience",progress.experience);body.addProperty("healthRatio",progress.health);
                     body.addProperty("isDead",progress.dead);body.add("cooldowns",new Gson().toJsonTree(progress.cooldowns));
                     RuntimeAccountAuthority authority = progressAuthorityProvider.apply(state.id);
                     if (authority != null) authority.writeTo(body);
                     progress.request=new ProgressRequest(body,progress.experience,progress.revision);
                }
                request=progress.request;
            }
            JsonObject result=repository.mutate(state.id,"/"+id+"/progress","POST",request.body);
            PetInstance changed=new PetInstance(object(result,"instance"));
            synchronized(state){
                state.pets.put(id,changed);progress.experience-=request.experience;progress.acknowledged=request.revision;progress.request=null;
                if(progress.revision==progress.acknowledged){progress.health=number(changed.details(),"healthRatio",1);progress.dead=changed.dead();}
            }
            updated=true;
        }
        if(updated&&plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->{
            if(accounts.get(state.id)!=state)return;
            if(adminGrowthPending.contains(state.id))return;
            for(AstPlayer owner:AstPlayerCache.getAll())
                if(owner.getAccount().getUuid().equals(state.id))progressListener.accept(owner);
        });
    }
    /**
     * 原子操作を実行します。支払い予約・事前保存・API・三者マージ・事後保存をまとめます。
     * @param owner 読み込み済みの主人
     * @param operationId 再送でも維持する操作ID
     * @param suffix アカウントAPIに続く操作パス
     * @param method HTTPメソッド
     * @param body 操作要求のJSON
     * @param payment 契約に従った入力値
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public CompletableFuture<JsonObject> mutate(AstPlayer owner,UUID operationId,String suffix,String method,JsonObject body,Map<String,Long> payment) {
        return mutate(owner, operationId, suffix, method, body, payment, null,null);
    }

    /**
     * 物理GAME装備枠7への移動・交換・解除を、BAG返却予約付きの原子操作で確定します。メインスレッド専用です。
     * @param owner 読み込み済みの所有者
     * @param petId 装備するPET個体ID。解除の場合はnull
     * @return API確定結果。事前拒否はfailureを含む完了future、通信失敗は例外future
     */
    public CompletableFuture<JsonObject> equip(AstPlayer owner,@Nullable UUID petId){
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId))return CompletableFuture.failedFuture(new IllegalStateException("Pet operation unresolved"));
        InventoryService.PetEquipmentPreparation prepared=inventory.preparePetEquipmentChange(owner,equippedId(accountId),petId);
        if(prepared.plan()==null){
            JsonObject denied=new JsonObject();denied.addProperty("failure",prepared.bagFull()?"bag_full":"pet_not_available");
            return CompletableFuture.completedFuture(denied);
        }
        JsonObject body=new JsonObject();
        if(petId==null)body.add("petId",JsonNull.INSTANCE);else body.addProperty("petId",petId.toString());
        if(prepared.plan().returnBagSlotIndex()!=null)body.addProperty("returnBagSlotIndex",prepared.plan().returnBagSlotIndex());
        return mutate(owner,UUID.randomUUID(),"/equipped","PUT",body,Map.of(),null,prepared.plan());
    }

    /** 卵・孵化済みペット付与のBAG予約を未確定操作へ接続し、同じ操作IDの復旧まで保持します。メインスレッド専用です。 */
    private CompletableFuture<JsonObject> mutate(AstPlayer owner,UUID operationId,String suffix,String method,
        JsonObject body,Map<String,Long> payment,InventoryService.PreparedInstanceSlotReservation reservation) {
        return mutate(owner,operationId,suffix,method,body,payment,reservation,null);
    }
    /** ペット個体生成または既存PETの装備移動予約を保持したまま原子APIへ渡します。 */
    private CompletableFuture<JsonObject> mutate(AstPlayer owner,UUID operationId,String suffix,String method,
        JsonObject body,Map<String,Long> payment,InventoryService.PreparedInstanceSlotReservation reservation,
        InventoryService.PetEquipmentPlan equipmentPlan) {
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId)||adminGrowthPending.contains(accountId)) {
            releaseSlotReservation(reservation);
            inventory.releasePetEquipmentPlan(equipmentPlan);
            return CompletableFuture.failedFuture(new IllegalStateException("Pet operation unresolved"));
        }
        var bagEntries = PetBagAccess.entries(inventory.getStateRegistry().get(accountId));
        if (payment.entrySet().stream().anyMatch(cost -> PetBagAccess.materialAmount(bagEntries, cost.getKey()) < cost.getValue())) {
            releaseSlotReservation(reservation);
            inventory.releasePetEquipmentPlan(equipmentPlan);
            JsonObject result = new JsonObject();
            result.addProperty("failure", "insufficient_materials");
            Logger.log(LogId.W_9601, accountId, suffix, 0, "insufficient_materials");
            return CompletableFuture.completedFuture(result);
        }
        if (!inventory.reserveOrbOperationPayment(accountId,operationId,payment,0)) {
            releaseSlotReservation(reservation);
            inventory.releasePetEquipmentPlan(equipmentPlan);
            JsonObject result = new JsonObject();
            result.addProperty("failure", "payment_unavailable");
            Logger.log(LogId.W_9601, accountId, suffix, 0, "payment_unavailable");
            return CompletableFuture.completedFuture(result);
        }
        body=body.deepCopy();body.addProperty("operationId",operationId.toString());body.addProperty("updatedBy",accountId.toString());
        ExternalOperation operation=new ExternalOperation(owner,operationId,suffix,method,body,reservation,equipmentPlan);
        if(unresolved.putIfAbsent(accountId,operation)!=null){releaseSlotReservation(reservation);inventory.releasePetEquipmentPlan(equipmentPlan);inventory.releaseOrbOperationPayment(accountId,operationId);return CompletableFuture.failedFuture(new IllegalStateException("Pet operation busy"));}
        if(!org.bukkit.Bukkit.isPrimaryThread()){
            releaseSlotReservation(reservation);inventory.releasePetEquipmentPlan(equipmentPlan);unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);
            return CompletableFuture.failedFuture(new IllegalStateException("Pet operation must begin on Bukkit main thread"));
        }
        pauseListener.accept(owner);
        AccountState state=accounts.get(accountId);
        if(state==null){releaseSlotReservation(reservation);inventory.releasePetEquipmentPlan(equipmentPlan);unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);
            return CompletableFuture.failedFuture(new IllegalStateException("Pet account is not loaded"));}
        CompletableFuture<JsonObject> future;
        synchronized(state){
            future=state.tail.handle((ignored,failure)->null).thenRunAsync(()->flushBlocking(state),executor)
                .thenCompose(ignored->saves.executeExclusiveAfterSave(accountId,operationId,baseline->{
                    operation.baseline=baseline;return perform(operation);
                }));
            state.tail=future.thenApply(ignored->null);
        }
        return future.whenComplete((result,failure)->{
            if(failure==null){finish(operation);}
            else if(operation.baseline==null){releaseSlotReservation(reservation);inventory.releasePetEquipmentPlan(equipmentPlan);unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);notifyRefresh(owner);}
            else {log(failure);}
        });
    }
    private JsonObject perform(ExternalOperation operation) {
        UUID accountId=operation.owner.getAccount().getUuid();
        if(operation.result==null) {
            try {operation.result=repository.mutate(accountId,operation.suffix,operation.method,operation.body);}
            catch(PetRepository.RejectedOperation rejected) {
                operation.rejected=rejected;operation.result=new JsonObject();
                operation.result.addProperty("failure", rejected.failureCode());
                Logger.log(LogId.W_9601, accountId, operation.suffix, rejected.status, rejected.failureCode());
            }
        }
        if(operation.rejected==null&&!operation.applied){
            if (!operation.inventoryApplied) {
                InventoryOperationSnapshot snapshot=InventoryOperationSnapshotParser.parse(operation.result.get("inventorySnapshot"));
                if(snapshot!=null) {
                    if (operation.reservation != null)
                        inventory.reconcileReservedPetGrant(accountId, operation.baseline, snapshot, operation.reservation);
                    else if(operation.equipmentPlan!=null)
                        inventory.reconcileReservedPetEquipment(accountId,operation.baseline,snapshot,operation.equipmentPlan);
                    else inventory.reconcileExternalInventoryEntries(accountId,snapshot.getCoveredEntryIds(),operation.baseline,snapshot);
                } else if(operation.result.has("inventorySnapshot") || operation.reservation != null||operation.equipmentPlan!=null)
                    throw new IllegalStateException("Invalid pet inventory snapshot");
                // 個体cacheの再取得に失敗しても、同じAPI差分を二度取り込まない。
                operation.inventoryApplied = true;
            }
            publishAccount(accountId,repository.account(accountId));
            operation.applied=true;
        }
        inventory.releaseOrbOperationPayment(accountId,operation.id);
        return operation.result;
    }
    private void finish(ExternalOperation operation) {
        releaseSlotReservation(operation.reservation);
        inventory.releasePetEquipmentPlan(operation.equipmentPlan);
        UUID accountId=operation.owner.getAccount().getUuid();unresolved.remove(accountId,operation);
        notifyRefresh(operation.owner);
        if (operation.applied && operation.rejected == null && operation.suffix.endsWith("/hatch")
            && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (AstPlayerCache.get(operation.owner.getBukkit()) == operation.owner) {
                    hatchSuccessListener.accept(operation.owner);
                }
            });
        }
    }
    /** 予約がある場合だけ解除します。解除済み予約にも安全に使用できます。 */
    private void releaseSlotReservation(InventoryService.PreparedInstanceSlotReservation reservation) {
        if (reservation != null) inventory.releasePreparedInstanceReservation(reservation);
    }
    private void notifyRefresh(AstPlayer owner){
        if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->refreshListener.accept(owner));
    }
    /**
     * 保存タイマーから進行保存と結果不明操作の同一ID再試行を要求します。HTTPを呼出threadでは待ちません。
     */
    public void tickSave() {
        if(stopped)return;
        for(AccountState state:accounts.values()){
            synchronized(state){if(!state.tail.isDone()||state.progress.values().stream().allMatch(value->value.revision==value.acknowledged))continue;}
            flush(state.id).exceptionally(failure->{log(failure);return null;});
        }
        for(ExternalOperation operation:unresolved.values()) {
            if(operation.baseline==null||operation.recovering)continue;
            operation.recovering=true;
            UUID accountId=operation.owner.getAccount().getUuid();
            saves.executeExclusiveAfterSaveRecovery(accountId,operation.id,ignored->perform(operation)).whenComplete((result,failure)->{
                operation.recovering=false;if(failure==null)finish(operation);else log(failure);
            });
        }
    }
    /**
     * 停止前の全個体を非同期で保存します。呼出元は終了時だけ有界待機できます。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public CompletableFuture<Void> stopAndFlush() {
        stopped=true;return CompletableFuture.allOf(accounts.keySet().stream().map(this::flush).toArray(CompletableFuture[]::new));
    }
    /**
     * 退出するキャッシュ世代を保持します。新しいログイン・再読込とは区別します。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public long generation(UUID accountId){AccountState state=accounts.get(accountId);if(state==null)return -1; synchronized(state){return state.generation;}}
    /**
     * 退出保存済みの同世代だけを解放します。新しいログインや未保存状態を消しません。
     * @param accountId 所有アカウントID
     * @param generation 退出時に取得したキャッシュ世代
     */
    public void releaseSavedAccount(UUID accountId,long generation){
        AccountState state=accounts.get(accountId);if(state==null)return;
        synchronized(state){
            if(state.generation==generation&&state.tail.isDone()&&!unresolved.containsKey(accountId)
                &&state.progress.values().stream().allMatch(value->value.request==null&&value.revision==value.acknowledged)){
                accounts.remove(accountId,state);legacyEquipAttempts.remove(accountId);
            }
        }
    }
    /**
     * 保存済みアカウントのキャッシュを破棄します。削除・明示復旧の境界で使用します。
     * @param accountId 所有アカウントID
     */
    public void forgetAccount(UUID accountId){
        if(unresolved.containsKey(accountId))throw new IllegalStateException("Pet operation unresolved during account discard");
        accounts.remove(accountId);legacyEquipAttempts.remove(accountId);
    }
    /**
     * API確定拒否かどうかを返します。未解決通信は別の失敗です。
     * @param result 契約に従った入力値
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean rejected(JsonObject result){return result==null||result.isEmpty()||result.has("failure");}
    private static void log(Throwable failure){Logger.log(LogId.E_9600,failure,"pet");}
    private static final class AccountState {
        final UUID id;Map<UUID,PetInstance> pets=new LinkedHashMap<>();UUID equipped;long generation;
        final Map<UUID,Progress> progress=new LinkedHashMap<>();CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
        AccountState(UUID id){this.id=id;}
    }
    private record LegacyEquipAttempt(UUID petId,long generation,long retryAfterNanos) { }
    private static final class Progress {
        long experience,revision,acknowledged;double health;boolean dead;Map<String,Long> cooldowns=new HashMap<>();ProgressRequest request;
        Progress(PetInstance pet){health=number(pet.details(),"healthRatio",1);dead=pet.dead();object(pet.details(),"cooldowns").entrySet().forEach(row->cooldowns.put(row.getKey(),row.getValue().getAsLong()));}
    }
    private record ProgressRequest(JsonObject body,long experience,long revision){}
    private static final class ExternalOperation {
        final AstPlayer owner;final UUID id;final String suffix,method;final JsonObject body;
        final InventoryService.PreparedInstanceSlotReservation reservation;
        final InventoryService.PetEquipmentPlan equipmentPlan;
        volatile InventoryPersistence.PersistedInventoryBaseline baseline;volatile JsonObject result;
        volatile PetRepository.RejectedOperation rejected;volatile boolean inventoryApplied,applied,recovering;
        ExternalOperation(AstPlayer owner,UUID id,String suffix,String method,JsonObject body,
            InventoryService.PreparedInstanceSlotReservation reservation,InventoryService.PetEquipmentPlan equipmentPlan){
            this.owner=owner;this.id=id;this.suffix=suffix;this.method=method;this.body=body;this.reservation=reservation;this.equipmentPlan=equipmentPlan;
        }
    }
}
