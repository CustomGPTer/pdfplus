# Shrink only: names are kept so crash reports stay readable.
-dontobfuscate
# MuPDF calls back into these classes from native code by name.
-keep class com.artifex.mupdf.fitz.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
