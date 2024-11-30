package com.azhon.appupdate.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream


/**
 * createDate:  2022/4/7 on 17:02
 * desc:
 *
 * @author azhon
 */

class ApkUtil {
    companion object {
        private const val TAG = "ApkUtil"

        /**
         * install package form file
         */
        fun installApk(
            context: Context,
            authorities: String,
            apk: File,
            bundleFile: Boolean = false
        ) {
            if (bundleFile) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    LogUtil.d(TAG, "APKM install not supported on this device")
                    return
                }
                installAPKM(context, apk)
                return
            }
            context.startActivity(createInstallIntent(context, authorities, apk))
        }

        fun createInstallIntent(context: Context, authorities: String, apk: File): Intent {
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(context, authorities, apk)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                uri = Uri.fromFile(apk)
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            return intent
        }

        fun getVersionCode(context: Context): Long {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                return packageInfo.versionCode.toLong()
            }
        }

        fun deleteOldApk(context: Context, oldApkPath: String): Boolean {
            val curVersionCode = getVersionCode(context)
            try {
                val apk = File(oldApkPath)
                if (apk.exists()) {
                    val oldVersionCode = getVersionCodeByPath(context, oldApkPath)
                    if (curVersionCode > oldVersionCode) {
                        return apk.delete()
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error delete old apk: ${e.message}")
            }
            return false
        }

        private fun getVersionCodeByPath(context: Context, path: String): Long {
            val packageInfo =
                context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode ?: 1
            } else {
                return packageInfo?.versionCode?.toLong() ?: 1
            }
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private fun installAPKM(context: Context, apkmFile: File) {
            val extractedFiles = extractAPKsFromAPKM(context, apkmFile)
            if (extractedFiles.isNotEmpty()) {
                installAPKs(context, extractedFiles)
            }
        }

        private fun extractAPKsFromAPKM(context: Context, apkmFile: File): List<File> {
            val extractedFiles = mutableListOf<File>()
            try {
                val outputDir = context.filesDir.resolve("extracted_apk")
                outputDir.deleteRecursively()
                outputDir.mkdirs()

                ZipInputStream(FileInputStream(apkmFile)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(".apk")) {
                            val outputFile = File(outputDir, entry.name)
                            FileOutputStream(outputFile).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            extractedFiles.add(outputFile)
                        }
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error extracting APKs: ${e.message}")
            }
            return extractedFiles
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private fun installAPKs(context: Context, apkFiles: List<File>) {
            try {
                val packageInstaller = context.packageManager.packageInstaller
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                apkFiles.forEach { apkFile ->
                    session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                        FileInputStream(apkFile).use { inp ->
                            inp.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }

                val packageManager = context.packageManager
                val intent = packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    if (context !is Activity) {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } ?: Intent().apply {
                    setPackage(context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error installing APKs: ${e.message}")
            }
        }
    }
}