import com.class_erp.schemas.AccessService
import schemas.ClassesListService
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import schemas.UploadService

object DatabaseConfig {
    val appModule = module {
        single {
            Database.connect(
                url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/effective_english_course",
                user = "dbmasteruser",
                driver = "com.mysql.cj.jdbc.Driver",
                password = "q1w2e3r4"
            )
        }

        single { AccessService(get()) }
        single { ClassesListService(get()) }
        single { UploadService(get()) }
    }
}
