package io.github.maaasu.astralRecord.feature.pet.command;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.pet.view.PetOperationFeedback;
import io.github.maaasu.astralRecord.feature.pet.service.PetService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** 管理者がペット施設操作、名前変更、装備中個体の成長を行うコマンドです。 */
public final class PetCommand extends AstCommand {
    /** プレイヤー専用のペット管理コマンドを登録します。 */
    public PetCommand(){super("pet","管理者用のペット施設画面を開きます。","/pet [name <名前>|level <目標成長度>]",true,99);}
    /** 読み込み済み本人の個体だけを操作し、保存処理はサービスへ委譲します。 */
    @Override protected void executePlayerCommand(@NotNull AstPlayer player,@NotNull String[] args){
        if(!player.hasAdminPermission()){PlayerMessageService.getInstance().send(player.getBukkit(),PlayerMsgId.P_5061);return;}
        if(!requireGameplayMode(player))return;
        AstralRecord plugin=AstralRecord.getInstance();
        if(args.length==0){if(plugin.getPetGui()!=null)plugin.getPetGui().openAdminFacility(player.getBukkit());return;}
        if(args[0].equalsIgnoreCase("level")){
            if(args.length!=2){sendUsage(player.getBukkit());return;}
            int target;
            try{target=Integer.parseInt(args[1]);}
            catch(NumberFormatException failure){sendUsage(player.getBukkit());return;}
            var service=plugin.getPetService();
            try{
                service.raiseEquippedLevel(player,target).whenComplete((level,failure)->{
                    if(!plugin.isEnabled())return;
                    Bukkit.getScheduler().runTask(plugin,()->{
                        if(AstPlayerCache.get(player.getBukkit())!=player)return;
                        if(failure==null)PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9626,level);
                        else if(failure.getCause() instanceof PetService.AdminGrowthPendingException)
                            PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9603);
                        else PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9619);
                    });
                });
            }catch(IllegalArgumentException failure){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9625);}
            catch(IllegalStateException failure){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9619);}
            return;
        }
        if(!args[0].equalsIgnoreCase("name")||args.length<2){sendUsage(player.getBukkit());return;}
        var service=plugin.getPetService();var id=service.equippedId(player.getAccount().getUuid());
        if(id==null){PlayerMessageService.getInstance().send(player,PlayerMsgId.P_9600);return;}
        JsonObject body=new JsonObject();body.addProperty("name",String.join(" ",Arrays.copyOfRange(args,1,args.length)));
        service.mutate(player,UUID.randomUUID(),"/"+id+"/rename","POST",body,Map.of()).whenComplete((result,failure)->{
            if(!plugin.isEnabled())return;
            Bukkit.getScheduler().runTask(plugin,()->PlayerMessageService.getInstance().send(player,
                PetOperationFeedback.message(result,failure,service.hasUnresolved(player.getAccount().getUuid()))));
        });
    }
}
