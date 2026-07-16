!macro NSIS_HOOK_POSTINSTALL
  IfSilent desktop_shortcut_done
  MessageBox MB_YESNO|MB_ICONQUESTION "Create an AetherGUI shortcut on the Desktop?" IDNO desktop_shortcut_done
  CreateShortCut "$DESKTOP\AetherGUI.lnk" "$INSTDIR\aether-gui.exe"
  desktop_shortcut_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  Delete "$DESKTOP\AetherGUI.lnk"
!macroend

