# P.R.I.S.M. Mobile keeps minification off for the experimental build.
# Rules reserved for when R8 is enabled.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.nevcortex.prism.** { *; }
