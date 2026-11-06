# Media3 and Coil ship their own consumer rules. The SAX parser is looked up reflectively.
-keep class org.xml.sax.** { *; }
-keep class org.apache.harmony.xml.** { *; }
-dontwarn org.xml.sax.**
