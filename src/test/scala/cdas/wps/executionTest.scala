package cdas.wps

import java.nio.file.Paths
import nasa.nccs.esgf.wps.{ProcessManager, wpsObjectParser}
import org.scalatest._
import scala.io.Source

class LocalExecutionTests extends LocalExecutionTestSuite {
  val fragment = getConfigValue("fragment")
  val varName = fragment.split('|').head
  val level = 30

  test("op") {
    val datainputs = "[domain=[{\"name\":\"d1\",\"lev\":{\"start\":%d,\"end\":%d,\"system\":\"indices\"}}],variable=[{\"uri\":\"fragment:/%s\",\"name\":\"%s:v1\",\"domain\":\"d1\"}],operation=[{\"name\":\"%s\",\"input\":\"v1\",\"axes\":\"t\"}]]".format(level, level, operation, fragment, varName)
    executeTest(datainputs)
  }
  test("anomaly_1D") {
    val datainputs = """[domain=[{"name":"d2","lat":{"start":30.0,"end":30.0,"system":"values"},"lon":{"start":30.0,"end":30.0,"system":"values"},"lev":{"start":%d,"end":%d,"system":"indices"}}],variable=[{"uri":"fragment:/%s","name":"%s:v1","domain":"d2"}],operation=[{"name":"CDS.anomaly","input":"v1","axes":"t"}]]""".format( level, level, fragment, varName )
    executeTest(datainputs)
  }
  test("subset_1D") {
    val datainputs =  """[domain=[{"name":"d2","lat":{"start":20.0,"end":20.0,"system":"values"},"lon":{"start":20.0,"end":20.0,"system":"values"},"lev":{"start":%d,"end":%d,"system":"indices"}}],variable=[{"uri":"fragment:/%s","name":"%s:v1","domain":"d2"}],operation=[{"name":"CDS.subset","input":"v1","axes":"t"}]]""".format( level, level, fragment, varName )
    executeTest(datainputs)
  }
  test("average_1D") {
    val datainputs =  """[domain=[{"name":"d2","lat":{"start":20.0,"end":20.0,"system":"values"},"lon":{"start":20.0,"end":20.0,"system":"values"},"lev":{"start":%d,"end":%d,"system":"indices"}}],variable=[{"uri":"fragment:/%s","name":"%s:v1","domain":"d2"}],operation=[{"name":"CDS.average","input":"v1","axes":"t"}]]""".format( level, level, fragment, varName )
    executeTest(datainputs)
  }
  test("subset_0D") {
    val datainputs = """[domain=[{"name":"d2","lat":{"start":20.0,"end":20.0,"system":"values"},"lon":{"start":20.0,"end":20.0,"system":"values"},"lev":{"start":%d,"end":%d,"system":"indices"},"time":{"start":100,"end":100,"system":"indices"}}],variable=[{"uri":"fragment:/%s","name":"%s:v1","domain":"d2"}],operation=[{"name":"CDS.subset","input":"v1","axes":"t"}]]""".format( level, level, fragment, varName )
    executeTest(datainputs)
  }
  test("yearly_cycle_1D") {
    val datainputs = """[domain=[{"name":"d2","lat":{"start":20.0,"end":20.0,"system":"values"},"lon":{"start":20.0,"end":20.0,"system":"values"},"lev":{"start":%d,"end":%d,"system":"indices"}}],variable=[{"uri":"fragment:/%s","name":"%s:v1","domain":"d2"}],operation=[{"name":"CDS.timeBin","input":"v1","axes":"t","unit":"month","period":"1","mod":"12"}]]""".format( level, level, fragment, varName )
    val response = executeTest(datainputs)
    assert( response != None, " Test completed ")
  }
}

class LocalExecutionTestSuite extends FunSuite with Matchers {
  val serverConfiguration = Map[String,String]()
  val webProcessManager = new ProcessManager( serverConfiguration )
  val service = "cds2"
  val identifier = "CDS.workflow"
  val operation = "CDS.sum"
  val config_file_path = Paths.get(  System.getProperty("user.home"), ".cdas", "test_config.txt" ).toString
  val config = getConfiguration

  def executeTest( datainputs: String, async: Boolean = false ): xml.Elem = {
    val t0 = System.nanoTime()
    val runargs = Map("responseform" -> "", "storeexecuteresponse" -> "true", "async" -> async.toString )
    val parsed_data_inputs = wpsObjectParser.parseDataInputs(datainputs)
    val response: xml.Elem = webProcessManager.executeProcess(service, identifier, parsed_data_inputs, runargs)
    webProcessManager.logger.info("Completed request '%s' in %.4f sec".format(identifier, (System.nanoTime() - t0) / 1.0E9))
    webProcessManager.logger.info(response.toString)
    response
  }

  def getConfigValue( key: String, defaultVal: Option[String] = None ): String = {
    config.get(key) match {
      case Some( value ) => value
      case None => defaultVal match {
        case Some( dval ) => dval
        case None => throw new Exception( "Config file '" + config_file_path + "' is missing required config value: " + key )
      }
    }
  }

  def getConfiguration: Map[String,String] = {
    try {
      val tuples = Source.fromFile(config_file_path).getLines.map(line => line.split('=')).toList
      try {
        Map[String, String](tuples.map(t => (t(0).trim -> t(1).trim)): _*)
      } catch {
        case err: ArrayIndexOutOfBoundsException => throw new Exception("Format error in config file: missing '='")
      }
    } catch {
      case  ex: java.io.FileNotFoundException => throw new Exception("Must create test config file: " + config_file_path )
    }
  }
}
