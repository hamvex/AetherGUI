!macro NSIS_HOOK_POSTINSTALL
  Delete "$DESKTOP\AetherGUI.lnk"
  IfSilent desktop_shortcut_done
  MessageBox MB_YESNO|MB_ICONQUESTION "Create a Firstham AetherGui shortcut on the Desktop?" IDNO desktop_shortcut_done
  CreateShortCut "$DESKTOP\Firstham AetherGui.lnk" "$INSTDIR\aether-gui.exe"
  desktop_shortcut_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ExecWait '"$INSTDIR\aether-gui.exe" --repair-network'
  Sleep 3000
  Delete "$DESKTOP\AetherGUI.lnk"
  Delete "$DESKTOP\Firstham AetherGui.lnk"
!macroend

