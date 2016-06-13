package nasa.nccs.cds2.loaders
import java.io.FileNotFoundException
import java.net.URL
import java.nio.file.{Files, Paths}

import nasa.nccs.cdapi.cdm.Collection
import nasa.nccs.utilities.Loggable

import scala.xml.XML

object AxisNames {
  def apply( x: String = "", y: String = "", z: String = "", t: String = "" ): Option[AxisNames] = {
    val nameMap = Map( 'x' -> x, 'y' -> y, 'z' -> z, 't' -> t )
    Some( new AxisNames( nameMap ) )
  }
}
class AxisNames( val nameMap: Map[Char,String]  ) {
  def apply( dimension: Char  ): Option[String] = nameMap.get( dimension ) match {
    case Some(name) => if (name.isEmpty) None else Some(name)
    case None=> throw new Exception( s"Not an axis: $dimension" )
  }
}

trait XmlResource extends Loggable {

  def getFilePath(resourcePath: String) = {
    val rpath = Option( getClass.getResource(resourcePath) ) match {
      case None => Option( getClass.getClassLoader.getResource(resourcePath) ) match {
        case None => throw new Exception(s"Resource $resourcePath does not exist!")
        case Some(r) => r.getPath
      }
      case Some(r) => r.getPath
    }
    logger.info( " $$$$$$$$$$$$$ Found Collection %s: path = %s ".format( resourcePath, rpath ) )
    rpath
  }

  def attr( node: xml.Node, att_name: String ) = { node.attribute(att_name) match { case None => ""; case Some(x) => x.toString }}
  def normalize(sval: String): String = sval.stripPrefix("\"").stripSuffix("\"").toLowerCase
  def nospace( value: String ): String  = value.filter(_!=' ')
}

object Mask  {
  def apply( mtype: String, resource: String ) = { new Mask(mtype,resource) }
}
class Mask( val mtype: String, val resource: String ) extends XmlResource {
  override def toString = "Mask( mtype=%s, resource=%s )".format( mtype, resource )
  def getPath: String = getFilePath( resource )
}

object Masks extends XmlResource {
  val mid_prefix: Char = '#'
  val masks = loadMaskXmlData(getFilePath("/masks.xml"))

  def isMaskId( maskId: String ): Boolean = (maskId(0) == mid_prefix )

  def loadMaskXmlData(filePath:String): Map[String,Mask] = {
    Map(XML.loadFile(filePath).child.flatMap( node => node.attribute("id") match {
      case None => None;
      case Some(id) => Some( (mid_prefix +: id.toString) -> createMask(node)); }
    ) :_* )
  }
  def createMask( n: xml.Node ): Mask = { Mask( attr(n,"mtype"), attr(n,"resource") ) }

  def getMask( id: String ): Option[Mask] = masks.get(id)

  def getMaskIds: Set[String] = masks.keySet
}

object Collections extends XmlResource {
  val datasets = loadCollectionXmlData( getFilePath("/global_collections.xml"), getFilePath("/local_collections.xml") )

  def toXml: xml.Elem = {
    <collections> { for( (id,collection) <- datasets ) yield <collection id={id}> {collection.vars.mkString(",")} </collection>} </collections>
  }
  def loadCollectionTextData(url:URL): Map[String,Collection] = {
    val lines = scala.io.Source.fromURL( url ).getLines
    val mapItems = for( line <- lines; toks =  line.split(';')) yield  nospace(toks(0)) -> Collection( ctype=nospace(toks(1)), url=nospace(toks(2)), vars=getVarList(toks(3)) )
    mapItems.toMap
  }

  def loadCollectionXmlData(filePaths:String*): Map[String,Collection] = {
    var elems = Seq[(String,Collection)]()
    for ( filePath <- filePaths; if Files.exists( Paths.get(filePath) ) ) {
      try {
        elems ++= XML.loadFile(filePath).child.flatMap(node => node.attribute("id") match {
          case None => None;
          case Some(id) => Some(id.toString.toLowerCase -> getCollection(node));
        })
      } catch { case err: java.io.IOException => throw new Exception( "Error opening collection data file {%s}: %s".format( filePath, err.getMessage) ) }
    }
    Map[String,Collection](elems:_*)
  }

  def getVarList( var_list_data: String  ): List[String] = var_list_data.filter(!List(' ','(',')').contains(_)).split(',').toList
  def getCollection( n: xml.Node ): Collection = { Collection( attr(n,"ctype"), attr(n,"url"), n.text.split(",").toList )}


  def toXml( collectionId: String ): xml.Elem = {
    datasets.get( collectionId ) match {
      case Some(collection) => <collection id={collectionId}> { collection.vars.mkString(",") } </collection>
      case None => <error> { "Invalid collection id:" + collectionId } </error>
    }
  }
  def parseUri( uri: String ): ( String, String ) = {
    if (uri.isEmpty) ("", "")
    else {
      val recognizedUrlTypes = List("file", "collection")
      val uri_parts = uri.split(":/")
      val url_type = normalize(uri_parts.head)
      if (recognizedUrlTypes.contains(url_type) && (uri_parts.length == 2)) (url_type, uri_parts.last)
      else throw new Exception("Unrecognized uri format: " + uri + ", type = " + uri_parts.head + ", nparts = " + uri_parts.length.toString + ", value = " + uri_parts.last)
    }
  }

  def getCollection(collection_uri: String, var_names: List[String] = List()): Option[Collection] = {
    parseUri(collection_uri) match {
      case (ctype, cpath) => ctype match {
        case "file" => Some(Collection(ctype = "file", url = collection_uri, vars = var_names))
        case "collection" =>
          val collection_key = cpath.stripPrefix("/").stripSuffix(""""""").toLowerCase
          logger.info( " getCollection( %s ) ".format(collection_key) )
          datasets.get( collection_key )
      }
    }
  }

  def getCollectionKeys: Array[String] = datasets.keys.toArray

}


object TestCollection extends App {
  println( Collections.datasets.toString )
}

object TestMasks extends App {
  println( Masks.masks.toString )
}





