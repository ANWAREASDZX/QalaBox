# الاحتفاظ بأساليب JNI في EmulatorActivity (تُستدعى من الطبقة الأصلية C)
-keepclasseswithmembernames class com.qalabox.emu.EmulatorActivity {
    native <methods>;
}
-keepclassmembers class com.qalabox.emu.EmulatorActivity {
    private void onFps(int);
    private void onCursorData(int,int,int,int,int,int,int[]);
}
