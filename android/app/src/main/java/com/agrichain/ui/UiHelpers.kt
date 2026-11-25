package com.agrichain.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.agrichain.R
import java.util.Locale

/** Shared visual helpers for picture-first screens across the app. */
object UiHelpers {

    fun cropIcon(crop: String?): Int = when (crop?.trim()?.lowercase(Locale.US)) {
        "wheat" -> R.drawable.ill_crop_wheat
        "rice" -> R.drawable.ill_crop_rice
        "maize" -> R.drawable.ill_crop_maize
        "cotton" -> R.drawable.ill_crop_cotton
        "chilli" -> R.drawable.ill_crop_chilli
        else -> R.drawable.ill_farmer
    }

    fun cropTitle(crop: String?): String {
        val clean = crop?.trim().orEmpty()
        if (clean.isEmpty()) return "Crop"
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    fun friendlyActor(actorId: String?): String {
        val raw = actorId?.trim().orEmpty()
        if (raw.isEmpty()) return "Unknown"
        val role = raw.substringBefore(':', raw)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val suffix = raw.substringAfter(':', "")
        return if (suffix.length >= 4) "$role #${suffix.take(4)}" else raw
    }

    fun eventIcon(type: String?): Int = when (type?.uppercase(Locale.US)) {
        "REGISTER" -> R.drawable.ill_farmer
        "BIND" -> R.drawable.ic_lock
        "TRANSFER" -> R.drawable.ic_swap
        "GPS" -> R.drawable.ic_gps
        "TAMPER" -> R.drawable.ic_warning
        "DELIVER" -> R.drawable.ic_check
        "BARTER" -> R.drawable.ill_barter
        else -> R.drawable.ic_check
    }

    fun eventTitle(type: String?): String = when (type?.uppercase(Locale.US)) {
        "REGISTER" -> "Harvest Recorded"
        "BIND" -> "Box Locked On"
        "TRANSFER" -> "Crop Sold / Moved"
        "GPS" -> "Location Checked"
        "TAMPER" -> "Tamper Alarm!"
        "DELIVER" -> "Delivered"
        "BARTER" -> "Crop Swap"
        else -> type.orEmpty()
    }

    fun eventColor(context: Context, type: String?): Int = context.getColor(
        when (type?.uppercase(Locale.US)) {
            "REGISTER", "DELIVER" -> R.color.status_ok
            "TAMPER" -> R.color.status_alert
            "BARTER" -> R.color.barter_purple
            "TRANSFER" -> R.color.status_warn
            else -> R.color.buyer_blue
        }
    )

    fun wireToggle(header: View, content: View, chevron: ImageView? = null, startOpen: Boolean = false) {
        content.visibility = if (startOpen) View.VISIBLE else View.GONE
        chevron?.rotation = if (startOpen) 180f else 0f
        header.setOnClickListener {
            val open = content.visibility != View.VISIBLE
            content.visibility = if (open) View.VISIBLE else View.GONE
            chevron?.animate()?.rotation(if (open) 180f else 0f)?.setDuration(150)?.start()
        }
    }
}
