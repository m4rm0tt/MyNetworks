# SQLCipher : classes appelées depuis le code natif (JNI)
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
