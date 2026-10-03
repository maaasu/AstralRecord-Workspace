package io.github.maaasu.astralRecord.feature.pet.command;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.pet.view.PetOperationFeedback;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** ペット管理と、装備中の個体の名前変更を提供します。 */
public final class PetCommand extends AstCommand {
    /** プレイヤー専用のペット管理コマンドを登録します。 */
    public PetCommand(){super("pet","ペット管理画面を開きます。","/pet [name <名前>]",true);}
    /** 読み込み済み本人の個体だけを操作し、保存処理はサービスへ委譲します。 */
    @Override protected void executePlayerCommand(@NotNull AstPlayer player,@NotNull String[] args){
        if(!requireGameplayMode(player))return;
        AstralRecord plugin=AstralRecord.getInstance();
        if(args.length==0){if(plugin.getPetGui()!=null)plugin.getPetGui().open(player.getBukkit());return;}
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
