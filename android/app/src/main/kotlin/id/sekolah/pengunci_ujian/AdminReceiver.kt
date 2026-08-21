package id.sekolah.pengunci_ujian

import android.app.admin.DeviceAdminReceiver

/**
 * Titik masuk Device Owner (opsional).
 *
 * Provisioning pada perangkat yang belum punya akun Google:
 *   adb shell dpm set-device-owner id.sekolah.pengunci_ujian/.AdminReceiver
 *
 * Melepas:
 *   adb shell dpm remove-active-admin id.sekolah.pengunci_ujian/.AdminReceiver
 *
 * Tanpa ini aplikasi tetap berfungsi, hanya memakai screen pinning biasa.
 */
class AdminReceiver : DeviceAdminReceiver()
