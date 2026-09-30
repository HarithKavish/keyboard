# The InputMethodService and the settings Activity are instantiated by the
# framework from the names in AndroidManifest.xml, never from our own code, so
# R8 cannot see the references and would otherwise strip or rename them.
-keep class com.harithkavish.keyboard.GlassKeyboardService { *; }
-keep class com.harithkavish.keyboard.SetupActivity { *; }
