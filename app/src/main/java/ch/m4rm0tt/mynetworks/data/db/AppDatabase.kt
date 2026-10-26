package ch.m4rm0tt.mynetworks.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [EntityRow::class, TagRow::class, EntityTagRow::class, ManualLinkRow::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NetworkDao

    companion object {
        const val NAME = "mynetworks.db"

        /**
         * Base SQLCipher (AES-256) dont la phrase secrète est protégée par Android Keystore.
         * Aucune migration destructive : en cas de schéma inconnu, Room lève une erreur et la
         * base reste intacte (jamais de fallbackToDestructiveMigration).
         */
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
