package io.github.maaasu.astralRecord.feature.pet.service;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.pet.model.*;
import io.github.maaasu.astralRecord.feature.pet.repository.PetRepository;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService.RuntimeAccountAuthority;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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
    private volatile PetMaster master = new PetMaster(new JsonObject());
    private volatile Consumer<AstPlayer> refreshListener = ignored -> { };
    private volatile Consumer<AstPlayer> pauseListener = ignored -> { };
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
                    for(AstPlayer player:io.github.maaasu.astralRecord.feature.player.AstPlayerCache.getAll())
                        if(player.getAccount().getUuid().equals(id))refreshListener.accept(player);
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
     * 召喚状態の再描画通知先を設定します。通知はメインスレッドで実行します。
     * @param listener 呼出threadの契約に従う通知先
     */
    public void setRefreshListener(Consumer<AstPlayer> listener) { refreshListener=listener; }
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
            number(details,"size",1),healthRatio(pet),dead(pet),
            new PetRuntimeService.PetCoefficients(coefficient(stats,definitions,"VITALITY"),coefficient(stats,definitions,"POWER"),
                coefficient(stats,definitions,"DEFENSE"),coefficient(stats,definitions,"EVASION"),coefficient(stats,definitions,"SUPPORT")),
            List.copyOf(skills),Map.copyOf(cooldowns),basicAttack(species));
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
    @Override public void grantExperience(AstPlayer owner,long amount) {
        if(amount<=0||!activityEligible.test(owner))return;
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId))return;
        AccountState state=accounts.get(accountId);if(state==null)return;
        synchronized(state){
            PetInstance pet=state.equipped==null?null:state.pets.get(state.equipped);
            if(pet==null||pet.isEgg()||dead(pet))return;
            Progress progress=state.progress.computeIfAbsent(pet.id(),ignored->new Progress(pet));
            progress.experience=Math.addExact(progress.experience,amount);progress.revision++;
        }
    }
    @Override public List<String> eggSpecies(){return master.speciesIds();}
    @Override public double eggDropChance(){return number(master.rules(),"eggDropChance",0);}
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
        }
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
        UUID accountId=owner.getAccount().getUuid();
        if(unresolved.containsKey(accountId))return CompletableFuture.failedFuture(new IllegalStateException("Pet operation unresolved"));
        if(!inventory.reserveOrbOperationPayment(accountId,operationId,payment,0))
            return CompletableFuture.failedFuture(new IllegalStateException("Pet payment unavailable"));
        body=body.deepCopy();body.addProperty("operationId",operationId.toString());body.addProperty("updatedBy",accountId.toString());
        ExternalOperation operation=new ExternalOperation(owner,operationId,suffix,method,body);
        if(unresolved.putIfAbsent(accountId,operation)!=null){inventory.releaseOrbOperationPayment(accountId,operationId);return CompletableFuture.failedFuture(new IllegalStateException("Pet operation busy"));}
        if(!org.bukkit.Bukkit.isPrimaryThread()){
            unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);
            return CompletableFuture.failedFuture(new IllegalStateException("Pet operation must begin on Bukkit main thread"));
        }
        pauseListener.accept(owner);
        AccountState state=accounts.get(accountId);
        if(state==null){unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);
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
            else if(operation.baseline==null){unresolved.remove(accountId,operation);inventory.releaseOrbOperationPayment(accountId,operationId);notifyRefresh(owner);}
            else {log(failure);}
        });
    }
    private JsonObject perform(ExternalOperation operation) {
        UUID accountId=operation.owner.getAccount().getUuid();
        if(operation.result==null) {
            try {operation.result=repository.mutate(accountId,operation.suffix,operation.method,operation.body);}
            catch(PetRepository.RejectedOperation rejected) {operation.rejected=rejected;operation.result=new JsonObject();}
        }
        if(operation.rejected==null&&!operation.applied){
            InventoryOperationSnapshot snapshot=InventoryOperationSnapshotParser.parse(operation.result.get("inventorySnapshot"));
            if(snapshot!=null) inventory.reconcileExternalInventoryEntries(accountId,snapshot.getCoveredEntryIds(),operation.baseline,snapshot);
            else if(operation.result.has("inventorySnapshot"))throw new IllegalStateException("Invalid pet inventory snapshot");
            publishAccount(accountId,repository.account(accountId));
            operation.applied=true;
        }
        inventory.releaseOrbOperationPayment(accountId,operation.id);
        return operation.result;
    }
    private void finish(ExternalOperation operation) {
        UUID accountId=operation.owner.getAccount().getUuid();unresolved.remove(accountId,operation);
        notifyRefresh(operation.owner);
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
                &&state.progress.values().stream().allMatch(value->value.request==null&&value.revision==value.acknowledged))accounts.remove(accountId,state);
        }
    }
    /**
     * 保存済みアカウントのキャッシュを破棄します。削除・明示復旧の境界で使用します。
     * @param accountId 所有アカウントID
     */
    public void forgetAccount(UUID accountId){
        if(unresolved.containsKey(accountId))throw new IllegalStateException("Pet operation unresolved during account discard");
        accounts.remove(accountId);
    }
    /**
     * API確定拒否かどうかを返します。未解決通信は別の失敗です。
     * @param result 契約に従った入力値
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public boolean rejected(JsonObject result){return result.isEmpty();}
    private static void log(Throwable failure){Logger.log(LogId.E_9600,failure,"pet");}
    private static final class AccountState {
        final UUID id;Map<UUID,PetInstance> pets=new LinkedHashMap<>();UUID equipped;long generation;
        final Map<UUID,Progress> progress=new LinkedHashMap<>();CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
        AccountState(UUID id){this.id=id;}
    }
    private static final class Progress {
        long experience,revision,acknowledged;double health;boolean dead;Map<String,Long> cooldowns=new HashMap<>();ProgressRequest request;
        Progress(PetInstance pet){health=number(pet.details(),"healthRatio",1);dead=pet.dead();object(pet.details(),"cooldowns").entrySet().forEach(row->cooldowns.put(row.getKey(),row.getValue().getAsLong()));}
    }
    private record ProgressRequest(JsonObject body,long experience,long revision){}
    private static final class ExternalOperation {
        final AstPlayer owner;final UUID id;final String suffix,method;final JsonObject body;
        volatile InventoryPersistence.PersistedInventoryBaseline baseline;volatile JsonObject result;
        volatile PetRepository.RejectedOperation rejected;volatile boolean applied,recovering;
        ExternalOperation(AstPlayer owner,UUID id,String suffix,String method,JsonObject body){this.owner=owner;this.id=id;this.suffix=suffix;this.method=method;this.body=body;}
    }
}
