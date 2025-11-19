import com.class_erp.schemas.AccessService
import schemas.classes.ClassesListService
import org.jetbrains.exposed.sql.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import schemas.estrelasLeiria.CategoriaService
import schemas.estrelasLeiria.IndicadoService
import schemas.users.ClientService
import schemas.mec.PriceTableMecService
import schemas.mec.RevenueService
import schemas.mec.ServiceOrderService
import schemas.mec.VehicleService

object DatabaseConfig {
    val classModule = module {
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

    val clientModule = module {
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
    val mecModule = module {
        single(named("MecDB")) {
            Database.connect(
                url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/mec",
                user = "dbmasteruser",
                driver = "com.mysql.cj.jdbc.Driver",
                password = "q1w2e3r4"
            )
        }

        single { ClientMecService(get(named("MecDB"))) }
        single { ExpenseService(get(named("MecDB"))) }
        single { PriceTableMecService(get(named("MecDB"))) }
        single { RevenueService(get(named("MecDB"))) }
        single { ServiceOrderService(get(named("MecDB"))) }
        single { VehicleService(get(named("MecDB"))) }
    }

    val estrelasLeiria = module {
        single(named("EstrelasLeiriaDB")) {
            Database.connect(
                url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/estrelas",
                user = "dbmasteruser",
                driver = "com.mysql.cj.jdbc.Driver",
                password = "q1w2e3r4"
            )
        }

        single { CategoriaService(get(named("EstrelasLeiriaDB"))) }
        single { IndicadoService(get(named("EstrelasLeiriaDB"))) }
    }
}
