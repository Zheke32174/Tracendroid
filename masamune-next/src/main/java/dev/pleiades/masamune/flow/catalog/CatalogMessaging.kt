package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * SMS, MMS, e-mail and Firebase cloud messages.
 *
 * The `Compose *` blocks hand a draft to whichever app the user has and so need no permission;
 * the `send` blocks put a message on the wire themselves and carry the SMS permissions. The
 * two await blocks split the same way - receiving needs [RECEIVE_SMS], watching what this
 * device sends needs [READ_SMS].
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val MESSAGING_BLOCKS: List<BlockSpec> = category(BlockCategory.MESSAGING) {
    action(
        "cloud_message_receive", "Cloud message receive",
        "Receives a \"cloud\" message sent through the internet from another device running " +
            "Automate or the online endpoint.",
        args = listOf(
            text("toAccount", "To Google account"),
            text("cipherAccount", "Cipher account", "unencrypted messages only"),
        ),
        outputs = listOf(
            out("varFromAccount", "From Google account"),
            out("varFromDevice", "From device"),
            out("varPayload", "Payload"),
        ),
    )
    action(
        "cloud_message_send", "Cloud message send",
        "Sends a \"cloud\" message through the internet to another device running Automate.",
        proceed = AWAIT,
        args = listOf(
            text("fromAccount", "From Google account"),
            any("toAccount", "To Google account"),
            any("toDevice", "To device", "all devices"),
            text("cipherAccount", "Cipher account", "unencrypted"),
            flag("highPriority", "Priority"),
            any("payload", "Payload"),
        ),
    )
    action(
        "compose_mms", "Compose MMS",
        "Composes an MMS in the default messaging app, user has to send.",
        args = listOf(
            text("phoneNumber", "Phone number"),
            text("subject", "Subject"),
            text("message", "Message"),
            text("attachment", "Attachment"),
            text("packageName", "Package", "system preferred Messaging app"),
        ),
    )
    action(
        "compose_sms", "Compose SMS",
        "Composes an SMS in the default messaging app, user has to send.",
        args = listOf(
            text("phoneNumber", "Phone number"),
            text("message", "Message"),
            text("packageName", "Package", "system preferred Messaging app"),
        ),
    )
    action(
        "compose_email", "Compose e-mail",
        "Composes an message in the default e-mail app, user has to send.",
        args = listOf(
            text("to", "To"),
            text("cc", "CC"),
            text("bcc", "BCC"),
            text("subject", "Subject"),
            text("message", "Message"),
            arr("attachments", "Attachments"),
            text("packageName", "Package", "system preferred e-mail app"),
        ),
    )
    action(
        "email_send", "E-mail send",
        "Sends an e-mail message via an SMTP server without user interaction.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "25, or 465 for SSL/TLS"),
            text("account", "Log in account", "no log in"),
            any("security", "Connection security"),
            flag("trust", "Certificate"),
            text("from", "From", "account username"),
            text("to", "To"),
            any("cc", "CC"),
            any("bcc", "BCC"),
            text("subject", "Subject"),
            text("message", "Message"),
            arr("attachments", "Attachments"),
        ),
    )
    action(
        "gmail_send", "Gmail send",
        "Sends an e-mail message with Gmail (Google Mail) without user interaction.",
        args = listOf(
            text("account", "Google account"),
            text("to", "To"),
            any("cc", "CC"),
            any("bcc", "BCC"),
            text("subject", "Subject"),
            text("message", "Message"),
            arr("attachments", "Attachments"),
        ),
    )
    action(
        "gmail_unread_count", "Gmail unread count",
        "Gets unread conversation count from the Gmail app.",
        args = listOf(
            text("account", "Google account"),
            any("inbox", "Inbox", "Regular inbox"),
        ),
        outputs = listOf(
            out("varUnreadCount", "Unread count"),
        ),
    )
    action(
        "mms_send", "MMS send",
        "Sends an MMS without user interaction.",
        args = listOf(
            text("phoneNumber", "Phone number"),
            text("subscriptionId", "Subscription id", "the system default SMS subscription"),
            text("subject", "Subject"),
            text("message", "Message"),
            text("attachment", "Attachment"),
            flag("hidden", "Hidden"),
        ),
        requires = setOf(SEND_SMS),
    )
    action(
        "sms_received", "SMS received",
        "Awaits an incoming SMS.",
        args = listOf(
            text("phoneNumber", "Phone number", "any phone number"),
            text("subscriptionId", "Subscription id", "any subscription"),
        ),
        outputs = listOf(
            out("varPhoneNumber", "Phone number"),
            out("varSubscriptionId", "Used subscription id"),
            out("varMessage", "Message"),
            out("varTimestamp", "Timestamp sent"),
        ),
        requires = setOf(RECEIVE_SMS),
    )
    action(
        "sms_send", "SMS send",
        "Sends an SMS without user interaction.",
        proceed = AWAIT,
        args = listOf(
            text("phoneNumber", "Phone number"),
            text("subscriptionId", "Subscription id", "the system default SMS subscription"),
            text("message", "Message"),
            num("multipartLimit", "Multipart limit", "1"),
            flag("hidden", "Hidden"),
        ),
        outputs = listOf(
            out("varMultipartCount", "Multipart count"),
        ),
        requires = setOf(SEND_SMS),
    )
    action(
        "sms_sent", "SMS sent",
        "Awaits an outgoing SMS.",
        args = listOf(
            text("phoneNumber", "Phone number", "any phone number"),
            text("subscriptionId", "Subscription id", "any"),
        ),
        outputs = listOf(
            out("varPhoneNumber", "Phone number"),
            out("varSubscriptionId", "Used subscription id"),
            out("varMessage", "Message"),
            out("varTimestamp", "Timestamp sent"),
        ),
        requires = setOf(READ_SMS),
    )
}
