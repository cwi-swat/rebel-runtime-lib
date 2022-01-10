import com.ing.corebank.rebel.sharding.Simple
import io.circe.Encoder
import io.circe.generic.auto._

Encoder[Simple.Event].apply(Simple.OnlyCommand).noSpaces