import com.class_erp.schemas.AccessService
import schemas.ClassesListService
import org.jetbrains.exposed.sql.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import schemas.ClientService
import schemas.UploadService

object DatabaseConfig {
    val appMain = module {
        single(named("MainDB")) {

            Database.connect(
                url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/effective_english_course",
                user = "dbmasteruser",
                driver = "com.mysql.cj.jdbc.Driver",
                password = "q1w2e3r4"
            )
        }

        single { AccessService(get(named("MainDB"))) }
        single { ClassesListService(get(named("MainDB"))) }
        single { UploadService(get(named("MainDB"))) }
    }

    val appClient = module {
        single(named("ClientDB")) {
            Database.connect(
                url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/Users",
                user = "dbmasteruser",
                driver = "com.mysql.cj.jdbc.Driver",
                password = "q1w2e3r4"
            )
        }

        single { ClientService(get(named("ClientDB"))) }
    }
}
