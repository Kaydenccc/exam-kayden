#ifndef RUNNER_KIOSK_HOOK_H_
#define RUNNER_KIOSK_HOOK_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

namespace pengunci_ujian {

void RegisterKioskChannel(flutter::PluginRegistrarWindows* registrar);

// Manual control (dipanggil juga oleh channel handler)
void InstallKeyboardHook();
void UninstallKeyboardHook();

}  // namespace pengunci_ujian

#endif  // RUNNER_KIOSK_HOOK_H_
