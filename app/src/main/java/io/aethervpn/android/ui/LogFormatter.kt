package io.aethervpn.android.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import io.aethervpn.android.R

object LogFormatter {
    private val TAG_REGEX = Regex("""^(\d{2}:\d{2}:\d{2})\s+\[([^\]]+)\]\s*(.*)$""")

    fun formatLine(context: Context, line: String): CharSequence {
        val colorTimestamp = ContextCompat.getColor(context, R.color.log_timestamp)
        val colorTagNative = ContextCompat.getColor(context, R.color.log_tag_native)
        val colorTagVpn = ContextCompat.getColor(context, R.color.log_tag_vpn)
        val colorTagAether = ContextCompat.getColor(context, R.color.log_tag_aether)
        val colorTagOther = ContextCompat.getColor(context, R.color.log_tag_other)
        val colorError = ContextCompat.getColor(context, R.color.log_error)
        val colorSuccess = ContextCompat.getColor(context, R.color.log_success)
        val colorText = ContextCompat.getColor(context, R.color.log_text)

        val builder = SpannableStringBuilder()
        val match = TAG_REGEX.find(line)
        if (match != null) {
            val (timestamp, tag, body) = match.destructured

            // 1. Timestamp (muted monospace)
            val tsStart = builder.length
            builder.append(timestamp)
            builder.setSpan(
                ForegroundColorSpan(colorTimestamp),
                tsStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            builder.append(' ')

            // 2. Tag (bold & distinct category color)
            val tagStart = builder.length
            builder.append("[$tag]")
            val tagColor = when {
                tag.contains("Native", ignoreCase = true) || tag.contains("JNI", ignoreCase = true) -> colorTagNative
                tag.contains("VPN", ignoreCase = true) || tag.contains("Tunnel", ignoreCase = true) -> colorTagVpn
                tag.contains("Aether", ignoreCase = true) -> colorTagAether
                else -> colorTagOther
            }
            builder.setSpan(
                ForegroundColorSpan(tagColor),
                tagStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                tagStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            builder.append(' ')

            // 3. Message Body (color-coded by severity)
            val bodyStart = builder.length
            builder.append(body)
            val bodyColor = when {
                body.contains("Error", ignoreCase = true) ||
                body.contains("fail", ignoreCase = true) ||
                body.contains("Exception", ignoreCase = true) ||
                body.contains("cannot locate symbol", ignoreCase = true) -> colorError

                body.contains("Connected", ignoreCase = true) ||
                body.contains("Ready", ignoreCase = true) ||
                body.contains("verified", ignoreCase = true) ||
                body.contains("success", ignoreCase = true) -> colorSuccess

                else -> colorText
            }
            builder.setSpan(
                ForegroundColorSpan(bodyColor),
                bodyStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            val start = builder.length
            builder.append(line)
            val color = if (line.contains("Error", ignoreCase = true) || line.contains("fail", ignoreCase = true)) {
                colorError
            } else {
                colorText
            }
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
    }

    fun format(context: Context, lines: List<String>): CharSequence {
        if (lines.isEmpty()) {
            return "No diagnostic events yet."
        }
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                builder.append('\n')
            }
            builder.append(formatLine(context, line))
        }
        return builder
    }
}
