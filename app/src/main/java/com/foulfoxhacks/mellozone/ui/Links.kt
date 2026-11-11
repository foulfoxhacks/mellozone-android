package com.foulfoxhacks.mellozone.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

private val OWN_HOSTS = setOf("mellozone.site", "www.mellozone.site")

/**
 * The default browser's package. Used when sending a mellozone.site link out
 * of the app, so Android does not route it straight back to this app.
 */
private fun browserPackage(context: Context): String? {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val info = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
    return info?.activityInfo?.packageName?.takeIf { it != context.packageName && it != "android" }
}

fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    val intent = when (uri.scheme) {
        "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
        "tel" -> Intent(Intent.ACTION_DIAL, uri)
        "http", "https" -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        else -> return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (uri.host in OWN_HOSTS) browserPackage(context)?.let(intent::setPackage)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this device can open that link.", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "That link could not be opened.", Toast.LENGTH_SHORT).show()
    }
}

fun sharePage(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, "Share page").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nothing on this device can share links.", Toast.LENGTH_SHORT).show()
    }
}
