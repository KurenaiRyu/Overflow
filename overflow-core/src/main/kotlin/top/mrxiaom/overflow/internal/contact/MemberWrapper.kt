@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package top.mrxiaom.overflow.internal.contact

import cn.evole.onebot.sdk.entity.Anonymous
import cn.evole.onebot.sdk.response.group.GroupMemberInfoResp
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.active.MemberActive
import net.mamoe.mirai.event.broadcast
import net.mamoe.mirai.event.events.EventCancelledException
import net.mamoe.mirai.event.events.GroupTempMessagePostSendEvent
import net.mamoe.mirai.event.events.GroupTempMessagePreSendEvent
import net.mamoe.mirai.event.events.MemberCardChangeEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.action.MemberNudge
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.MiraiInternalApi
import net.mamoe.mirai.utils.currentTimeSeconds
import top.mrxiaom.overflow.contact.Updatable
import top.mrxiaom.overflow.internal.Overflow
import top.mrxiaom.overflow.internal.check
import top.mrxiaom.overflow.internal.contact.data.EmptyMemberActive
import top.mrxiaom.overflow.internal.contact.data.MemberActiveWrapper
import top.mrxiaom.overflow.internal.message.OnebotMessages
import top.mrxiaom.overflow.internal.message.OnebotMessages.findForwardMessage
import top.mrxiaom.overflow.internal.message.data.OutgoingSource
import top.mrxiaom.overflow.internal.message.data.OutgoingSource.receipt
import top.mrxiaom.overflow.internal.utils.safeMessageIds
import top.mrxiaom.overflow.spi.FileService
import kotlin.coroutines.CoroutineContext

@OptIn(MiraiInternalApi::class)
internal class MemberWrapper(
    override val group: GroupWrapper,
    internal var impl: GroupMemberInfoResp
) : NormalMember, Updatable {
    override val bot: BotWrapper = group.bot
    val data: GroupMemberInfoResp
        get() = impl
    override suspend fun queryUpdate() {
        setImpl(bot.impl.getGroupMemberInfo(impl.groupId, impl.userId, false).data)
    }
    
    fun setImpl(impl: GroupMemberInfoResp) {
        if (impl.card != impl.card) {
            bot.eventDispatcher.broadcastAsync(
                MemberCardChangeEvent(this.impl.card, impl.card, this)
            )
        }
        this.impl = impl
    }

    override val active: MemberActiveWrapper = MemberActiveWrapper(this)
    override val id: Long = impl.userId
    override val joinTimestamp: Int
        get() = impl.joinTime
    override val lastSpeakTimestamp: Int
        get() = impl.lastSentTime
    override val muteTimeRemaining: Int
        get() = (impl.shutUpTimestamp - currentTimeSeconds()).takeIf { it > 0 }?.toInt() ?: 0

    override val coroutineContext: CoroutineContext = CoroutineName("((Bot/${bot.id})Group/${group.id})Member/$id")
    override var nameCard: String
        get() = impl.card ?: ""
        set(value) {
            if (id != bot.id) {
                group.checkBotPermission(MemberPermission.ADMINISTRATOR)
            }
            Overflow.instance.launch {
                bot.impl.setGroupCard(impl.groupId, id, value)
            }
        }
    override val nick: String
        get() = impl.nickname
    override val permission: MemberPermission
        get() = when(impl.role) {
            "owner" -> MemberPermission.OWNER
            "admin" -> MemberPermission.ADMINISTRATOR
            else -> MemberPermission.MEMBER
        }
    override val remark: String
        get() = bot.friends[id]?.remark ?: ""
    override var specialTitle: String
        get() = impl.title
        set(value) {
            Overflow.instance.launch {
                bot.impl.setGroupSpecialTitle(impl.groupId, id, value, -1)
            }
        }
    override suspend fun kick(message: String, block: Boolean) {
        checkBotPermissionHigherThanThis("移出本群")
        check(group.members[this.id] != null) {
            "群成员 ${this.id} 已经被踢出群 ${group.id} 了."
        }
        if (bot.impl.setGroupKick(impl.groupId, id, block)
            .check("将 $id 移出群聊 ${group.id}")) {
            group.members.remove(id)
        }
    }

    override suspend fun modifyAdmin(operation: Boolean) {
        checkBotPermissionHighest("设置管理员")
        if (bot.impl.setGroupAdmin(impl.groupId, id, operation)
            .check("设置 $id 在群聊 ${group.id} 的管理员状态为 $operation")) {
            impl.role = if (operation) "admin" else "member"
        }
    }

    override suspend fun mute(durationSeconds: Int) {
        check(this.id != bot.id) {
            "机器人不能禁言自己."
        }
        require(durationSeconds > 0) {
            "durationSeconds 必须要大于0."
        }
        checkBotPermissionHigherThanThis("禁言")
        if (bot.impl.setGroupBan(group.id, id, durationSeconds)
            .check("禁言群成员 $id")) {
            group.updateMember(id)
        }
    }

    override suspend fun unmute() {
        checkBotPermissionHigherThanThis("解除禁言")
        if (bot.impl.setGroupBan(group.id, id, 0)
            .check("解除禁言群成员 $id")) {
            group.updateMember(id)
        }
    }

    override fun nudge(): MemberNudge {
        return MemberNudge(this)
    }

    override suspend fun sendMessage(message: String): MessageReceipt<NormalMember> {
        return sendMessage(PlainText(message))
    }

    @OptIn(MiraiInternalApi::class)
    override suspend fun sendMessage(message: Message): MessageReceipt<NormalMember> {
        if (GroupTempMessagePreSendEvent(this, message).broadcast().isCancelled)
            throw EventCancelledException("消息发送已被取消")

        val messageChain = message.toMessageChain()
        var throwable: Throwable? = null
        val receipt = runCatching {
            val forward = message.findForwardMessage()
            val messageIds = if (forward != null) {
                OnebotMessages.sendForwardMessage(this, forward).safeMessageIds
            } else {
                val msg = OnebotMessages.serializeToOneBotJson(message)
                val response = bot.impl.sendPrivateMsg(id, msg, false)
                response.data.safeMessageIds
            }
            OutgoingSource.temp(
                bot = bot,
                ids = messageIds,
                internalIds = messageIds,
                isOriginalMessageInitialized = true,
                originalMessage = message.toMessageChain(),
                sender = bot,
                target = this,
                time = currentTimeSeconds().toInt()
            ).receipt(this)
        }.onFailure { throwable = it }.getOrNull()
        GroupTempMessagePostSendEvent(this, messageChain, throwable, receipt).broadcast()

        bot.logger.verbose("Member(${group.id}:$id) <- $messageChain")

        return receipt ?: throw throwable!!
    }

    override suspend fun uploadImage(resource: ExternalResource): Image {
        return OnebotMessages.imageFromFile(FileService.instance!!.upload(resource))
    }

    override suspend fun uploadShortVideo(
        thumbnail: ExternalResource,
        video: ExternalResource,
        fileName: String?
    ): ShortVideo {
        return OnebotMessages.videoFromFile(FileService.instance!!.upload(video))
    }

    override fun toString(): String = "NormalMember($id)"
}

internal class AnonymousMemberWrapper(
    override val group: GroupWrapper,
    internal var impl: Anonymous
) : AnonymousMember {
    override val bot: BotWrapper = group.bot
    override val anonymousId: String get() = impl.flag
    override val active: MemberActive = EmptyMemberActive
    override val nameCard: String get() = impl.name
    override val permission: MemberPermission = MemberPermission.MEMBER
    override val specialTitle: String get() = ""

    override val id: Long get() = impl.id
    override val remark: String get() = ""
    override val nick: String get() = impl.name
    override val coroutineContext: CoroutineContext = CoroutineName("((Bot/${bot.id})Group/${group.id})Anonymous/$id")

    override suspend fun mute(durationSeconds: Int) {
        checkBotPermissionHigherThanThis("禁言")

        if (bot.impl.setGroupAnonymousBan(group.id, anonymousId, durationSeconds)
                .check("禁言群成员 $id")) {
            group.updateMember(id)
        }
    }

    override fun toString(): String = "AnonymousMember($nameCard, $anonymousId)"
    override suspend fun uploadShortVideo(thumbnail: ExternalResource, video: ExternalResource, fileName: String?): ShortVideo =
        throw UnsupportedOperationException("Cannot upload short video to AnonymousMember")
}

internal fun Member.checkBotPermissionHighest(operationName: String) {
    check(group.botPermission == MemberPermission.OWNER) {
        throw PermissionDeniedException(
            "对群成员 $id 的操作 `$operationName` 需要 群主 权限, 但机器人在群 ${group.id} 内仅有 ${group.botPermission.display} 权限.",
        )
    }
}

internal fun Member.checkBotPermissionHigherThanThis(operationName: String) {
    check(group.botPermission > this.permission) {
        throw PermissionDeniedException(
            "对群 ${group.id} 的群成员 $id 的操作 `$operationName` 需要机器人比该成员拥有更高的权限, 但目前 " +
                    "${group.botPermission.display} < ${this.permission.display}.",
        )
    }
}

internal val MemberPermission.display: String
    get() = when(this) {
        MemberPermission.OWNER -> "群主"
        MemberPermission.ADMINISTRATOR -> "管理员"
        MemberPermission.MEMBER -> "群员"
    }
