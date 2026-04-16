#include "kiosk_hook.h"

#include <windows.h>
#include <memory>
#include <string>

namespace pengunci_ujian {

namespace {

HHOOK g_keyboard_hook = nullptr;

LRESULT CALLBACK LowLevelKeyboardProc(int nCode, WPARAM wParam, LPARAM lParam) {
  if (nCode == HC_ACTION) {
    KBDLLHOOKSTRUCT* p = reinterpret_cast<KBDLLHOOKSTRUCT*>(lParam);
    bool alt_down = (p->flags & LLKHF_ALTDOWN) != 0;
    bool ctrl_down = (GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0;
    bool shift_down = (GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0;

    // Blok Win key
    if (p->vkCode == VK_LWIN || p->vkCode == VK_RWIN) {
      return 1;
    }
    // Blok Alt+Tab
    if (alt_down && p->vkCode == VK_TAB) {
      return 1;
    }
    // Blok Alt+Esc
    if (alt_down && p->vkCode == VK_ESCAPE) {
      return 1;
    }
    // Blok Ctrl+Esc (Start menu)
    if (ctrl_down && p->vkCode == VK_ESCAPE) {
      return 1;
    }
    // Blok Alt+F4
    if (alt_down && p->vkCode == VK_F4) {
      return 1;
    }
    // Blok Ctrl+Shift+Esc (Task Manager)
    if (ctrl_down && shift_down && p->vkCode == VK_ESCAPE) {
      return 1;
    }
  }
  return CallNextHookEx(nullptr, nCode, wParam, lParam);
}

}  // namespace

void InstallKeyboardHook() {
  if (g_keyboard_hook) return;
  g_keyboard_hook =
      SetWindowsHookExW(WH_KEYBOARD_LL, LowLevelKeyboardProc,
                        GetModuleHandleW(nullptr), 0);
}

void UninstallKeyboardHook() {
  if (g_keyboard_hook) {
    UnhookWindowsHookEx(g_keyboard_hook);
    g_keyboard_hook = nullptr;
  }
}

void RegisterKioskChannel(flutter::PluginRegistrarWindows* registrar) {
  auto channel = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      registrar->messenger(), "id.sekolah.pengunci_ujian/kiosk",
      &flutter::StandardMethodCodec::GetInstance());

  channel->SetMethodCallHandler(
      [](const flutter::MethodCall<flutter::EncodableValue>& call,
         std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        const std::string& method = call.method_name();
        if (method == "startKiosk") {
          InstallKeyboardHook();
          result->Success(flutter::EncodableValue(true));
        } else if (method == "stopKiosk") {
          UninstallKeyboardHook();
          result->Success(flutter::EncodableValue(true));
        } else {
          result->NotImplemented();
        }
      });

  // Leak intentionally — harus hidup selama lifetime aplikasi
  (void)channel.release();
}

}  // namespace pengunci_ujian
