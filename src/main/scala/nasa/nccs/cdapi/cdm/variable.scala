package nasa.nccs.cdapi.cdm

import nasa.nccs.caching.{Partition, Partitions, RDDTransientVariable}
import nasa.nccs.cdapi.data.{HeapFltArray, RDDPartition, RDDVariableSpec}
import nasa.nccs.cdapi.tensors.{CDByteArray, CDFloatArray, CDIndexMap}
import nasa.nccs.cdas.engine.WorkflowNode
import nasa.nccs.esgf.process._
import ucar.{ma2, nc2, unidata}
import ucar.nc2.dataset.{CoordinateAxis1D, _}
import nasa.nccs.utilities.{Loggable, cdsutils}
import ucar.nc2.constants.AxisType

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

object BoundsRole extends Enumeration { val Start, End = Value }

object CDSVariable extends Loggable {
  def toCoordAxis1D(coordAxis: CoordinateAxis): CoordinateAxis1D = coordAxis match {
    case coordAxis1D: CoordinateAxis1D => coordAxis1D
    case _ => throw new IllegalStateException("CDSVariable: 2D Coord axes not yet supported: " + coordAxis.getClass.getName)
  }
  def empty = new CDSVariable( null, null )
}

class CDSVariable( val name: String, val collection: Collection ) extends Loggable with Serializable {
  val attributes: Map[String,nc2.Attribute] = nc2.Attribute.makeMap( collection.getVariableMetadata( name ) ).toMap
  val missing = getAttributeValue( "missing_value", "" ) match { case "" => Float.MaxValue; case s => s.toFloat }
  val description = getAttributeValue( "description", "" )
  val units = getAttributeValue( "units", "" )
  val dims = getAttributeValue( "dims", "" ).split(' ')
  val shape = getAttributeValue( "shape", "" ).split(',').map( _.toInt )
  val fullname = getAttributeValue( "fullname", "" )
  val section = new ma2.Section( shape )
  def getFullSection: ma2.Section = section
  def getAttributeValue( key: String, default_value: String  ) =  attributes.get( key ) match { case Some( attr_val ) => attr_val.toString.split('=').last.replace('"',' ').trim; case None => default_value }
  override def toString = "\nCDSVariable(%s) { description: '%s', shape: %s, dims: %s, }\n  --> Variable Attributes: %s".format(name, description, shape.mkString("[", " ", "]"), dims.mkString("[", ",", "]"), attributes.mkString("\n\t\t", "\n\t\t", "\n"))
  def normalize(sval: String): String = sval.stripPrefix("\"").stripSuffix("\"").toLowerCase
  def getAttributeValue( name: String ): String =  attributes.getOrElse(name, new nc2.Attribute(new unidata.util.Parameter("",""))).getValue(0).toString
  def toXml: xml.Node =
    <variable name={name} fullname={fullname} description={description} shape={shape.mkString("[", " ", "]")} units={units}>
      { for( dim: nc2.Dimension <- collection.grid.dimensions; name=dim.getFullName; dlen=dim.getLength ) yield getCoordinateAxis( name ) match {
          case None=> <dimension name={name} length={dlen.toString}/>
          case Some(axis)=>
              val units = axis.getUnitsString
              <dimension name={name} length={dlen.toString} start={axis.getStart.toString} units={units} step={axis.getIncrement.toString} cfname={axis.getAxisType.getCFAxisName}/>
        }
      }
      { for( name <- attributes.keys ) yield <attribute name={name}> { getAttributeValue(name) } </attribute> }
    </variable>


//  def read( section: ma2.Section ) = ncVariable.read(section)
  def getTargetGrid( fragSpec: DataFragmentSpec ): TargetGrid = fragSpec.targetGridOpt match { case Some(targetGrid) => targetGrid;  case None => new TargetGrid( this, Some(fragSpec.getAxes) ) }
  def getCoordinateAxes: List[ CoordinateAxis1D ] = {
    dims.flatMap( dim => collection.grid.findCoordinateAxis( dim ).map( coordAxis => CDSVariable.toCoordAxis1D( coordAxis ) ) ).toList
  }
  def getCoordinateAxis( axisType: AxisType ): Option[CoordinateAxis1D] = collection.grid.findCoordinateAxis(axisType).map( coordAxis => CDSVariable.toCoordAxis1D( coordAxis ) )
  def getCoordinateAxis( name: String ): Option[CoordinateAxis1D] = collection.grid.findCoordinateAxis(name).map( coordAxis => CDSVariable.toCoordAxis1D( coordAxis ) )
  def getCoordinateAxesList = collection.grid.getCoordinateAxes
}

trait OperationInput {
//  def domainDataFragment( partIndex: Int,  optSection: Option[ma2.Section] ): Option[DataFragment]
  def getKeyString: String
}
class EmptyOperationInput() extends OperationInput { def getKeyString: String = ""; }

class DependencyOperationInput( val workflowNode: WorkflowNode ) extends OperationInput with Loggable {
  def getKeyString: String =  workflowNode.getNodeId()
}

class OperationTransientInput( val variable: RDDTransientVariable ) extends OperationInput with Loggable {
//  def domainDataFragment( partIndex: Int,  optSection: Option[ma2.Section] ): Option[DataFragment] = variable.
  def getKeyString: String =  variable.request.getInputSpec match {
    case Some( dataFrag )=> dataFrag.getKeyString
    case None => variable.operation.inputs.mkString(":")
  }

}

abstract class OperationDataInput( val fragmentSpec: DataFragmentSpec, val metadata: Map[String,nc2.Attribute] ) extends OperationInput with Loggable {
  def toBoundsString = fragmentSpec.toBoundsString
  def getKey: DataFragmentKey = fragmentSpec.getKey
  def getKeyString: String = fragmentSpec.getKeyString
  def size: Int = fragmentSpec.roi.computeSize.toInt
  def contains( requestedSection: ma2.Section ): Boolean = fragmentSpec.roi.contains( requestedSection )
  def getVariableMetadata(serverContext: ServerContext): Map[String,nc2.Attribute] = { fragmentSpec.getVariableMetadata(serverContext) ++ metadata }
  def getDatasetMetadata(serverContext: ServerContext): List[nc2.Attribute] = { fragmentSpec.getDatasetMetadata(serverContext) }
  def data(partIndex: Int ): CDFloatArray
  def delete
}

class PartitionedFragment( val partitions: Partitions, val maskOpt: Option[CDByteArray], fragSpec: DataFragmentSpec, mdata: Map[String,nc2.Attribute] = Map.empty ) extends OperationDataInput(fragSpec,mdata) with Loggable {
  def delete = partitions.delete

  def data(partIndex: Int ): CDFloatArray = partitions.getPartData(partIndex, fragmentSpec.missing_value )

  def partFragSpec( partIndex: Int ): DataFragmentSpec = {
    val part = partitions.getPart(partIndex)
    fragmentSpec.reSection( part.partSection( fragmentSpec.roi ) )
  }

  def getGrid: TargetGrid = fragSpec.targetGridOpt match  {
    case Some( myGrid ) => myGrid
    case None => throw new Exception( "Undefined target grid in matchGrids for input " + fragSpec.uid )
  }
  def matchGrids( targetGrid: TargetGrid ): Boolean = fragSpec.targetGridOpt match  {
    case Some( myGrid ) => myGrid.equals( targetGrid )
    case None => throw new Exception( "Undefined target grid in matchGrids for input " + fragSpec.uid )
  }

  def domainFragSpec( partIndex: Int ): DataFragmentSpec = {
    val part = partitions.getPart(partIndex)
    fragmentSpec.domainSpec.reSection( part.partSection( fragmentSpec.roi ) )
  }

  def partDataFragment( partIndex: Int ): DataFragment = {
    val partition = partitions.getPart(partIndex)
    DataFragment( partFragSpec(partIndex), partition.data( fragmentSpec.missing_value ) )
  }

  def partRDDPartition( partIndex: Int ): RDDPartition = {
    val partition = partitions.getPart(partIndex)
    val data: CDFloatArray = partition.data( fragmentSpec.missing_value )
    val spec: DataFragmentSpec = partFragSpec(partIndex)
    RDDPartition( Map( spec.uid -> HeapFltArray(data, fragSpec.getOrigin, spec.getMetadata(), None) ) )
  }

  def domainRDDPartition(partIndex: Int, optSection: Option[ma2.Section] ): Option[RDDPartition] = domainCDDataSection( partIndex, optSection ) match {
    case Some((uid, section, metadata, data)) => Some(  RDDPartition( Map( uid -> HeapFltArray(data, section.getOrigin, metadata, None ) ) ) )
    case None => None
  }

  def domainDataFragment(partIndex: Int, optSection: Option[ma2.Section] ): Option[DataFragment] = domainDataSection( partIndex, optSection ) match {
    case Some((spec, data)) => Some( DataFragment(spec, data) )
    case None => None
  }

  def domainDataSection( partIndex: Int,  optSection: Option[ma2.Section] ): Option[ ( DataFragmentSpec, CDFloatArray )] = {
    try {
      val partition = partitions.getPart(partIndex)
      val partition_data = partition.data(fragmentSpec.missing_value)
      domainSection(partition, optSection) map {
        case (fragSpec, section) => (fragSpec, CDFloatArray(partition_data.section(section)))
      }
    } catch {
      case ex: Exception => logger.warn(s"Failed getting data fragment $partIndex: " + ex.toString)
        None
    }
  }

//  def domainDataFragment( partIndex: Int, context: CDASExecutionContext ): Option[DataFragment] = {
//    val optSection: Option[ma2.Section] = context.getOpSections match {
//      case None => return None
//      case Some( sections ) =>
////        logger.info( "OP sections: " + sections.map( _.toString ).mkString( "( ", ", ", " )") )
//        if( sections.isEmpty ) None
//        else {
//          val result = sections.foldLeft(sections.head)( _.intersect(_) )
////          logger.info( "OP sections: %s >>>>---------> intersection: %s".format( sections.map( _.toString ).mkString( "( ", ", ", " )"), result.toString ) )
//          if (result.computeSize() > 0) { Some(result) }
//          else return None
//        }
//    }
//  }

  def domainCDDataSection( partIndex: Int,  optSection: Option[ma2.Section] ): Option[ ( String, ma2.Section, Map[String,String], CDFloatArray )] = {
    try {
      val partition = partitions.getPart(partIndex)
      val partition_data = partition.data(fragmentSpec.missing_value)
      domainSection( partition, optSection ) map {
        case ( domFragSpec, section )  => ( domFragSpec.uid, section, domFragSpec.getMetadata(optSection), CDFloatArray( partition_data.section( section ) ) )
      }
    } catch {
      case ex: Exception => logger.warn( s"Failed getting data fragment $partIndex: " + ex.toString )
        None
    }
  }
  def getRDDVariableSpec( uid: String, partition: Partition,  optSection: Option[ma2.Section] ): RDDVariableSpec =
    domainSection(partition,optSection) match {
      case Some( ( domFragSpec, section ) ) =>
        new RDDVariableSpec( uid, domFragSpec.getMetadata(Some(section)), domFragSpec.missing_value, CDSection(section) )
      case _ =>
        new RDDVariableSpec( uid, fragSpec.getMetadata(), fragSpec.missing_value, CDSection.empty(fragSpec.getRank) )
    }


  def domainSection( partition: Partition,  optSection: Option[ma2.Section] ): Option[ ( DataFragmentSpec, ma2.Section )] = {
    try {
      val frag_section = partition.partSection(fragmentSpec.roi)
      val domain_section = fragmentSpec.domainSectOpt match {
        case Some(dsect) => frag_section.intersect(dsect)
        case None => frag_section
      }
      val partFragSpec = domainFragSpec(partition.index)
      val sub_section = optSection match {
        case Some(osect) =>
          val rv = domain_section.intersect( osect )
//          logger.info( "OP section intersect: " + osect.toString + ", result = " + rv.toString )
          rv
        case None =>
//          logger.info( "OP section empty" )
          domain_section
      }
      partFragSpec.cutIntersection( sub_section ) match {
        case Some( cut_spec: DataFragmentSpec ) =>
//          val offset: Array[Int] = (0 until frag_section.getRank).map( index => if( index == 0 ) frag_section.getOrigin(0) else 0 ).toArray
//          val array_section = cut_spec.roi.shiftOrigin( new ma2.Section( offset, Array.fill[Int](frag_section.getRank)(1) ) )
          Some( ( cut_spec, cut_spec.roi ) )
        case None =>None
      }
    } catch {
      case ex: Exception =>
        logger.warn( s"Failed getting data fragment " + partition.index + ": " + ex.toString )
        //        logger.error( ex.getStackTrace.mkString("\n\t") )
        None
    }
  }


      //      val domainDataOpt: Option[CDFloatArray] = fragmentSpec.domainSectOpt match {
//        case None => Some( partition.data(fragmentSpec.missing_value) )
//        case Some(domainSect) =>
//          val pFragSpec = partFragSpec( partIndex )
//          pFragSpec.cutIntersection(domainSect) match {
//            case Some(newFragSpec) =>
//              val dataSection = partition.getRelativeSection( newFragSpec.roi ).shiftOrigin( domainSect )
//              logger.info ("Domain Partition(%d) Fragment: fragSect=(%s), newFragSect=(%s), domainSect=(%s), dataSection=(%s), partition.shape=(%s)".format (partIndex, pFragSpec.roi.toString, newFragSpec.roi, domainSect.toString, dataSection.toString, partition.shape.mkString(",")) )
//              Some( partition.data (fragmentSpec.missing_value).section (dataSection.getRanges.toList) )
//            case None =>
//              logger.warn( "Domain Partition(%d) EMPTY INTERSECTION: fragSect=(%s), domainSect=(%s)".format (partIndex, pFragSpec.roi.toString, domainSect.toString) )
//              None
//          }
//      }
//      domainDataOpt.map( new DataFragment(domainFragSpec(partIndex), _ ) )


  def isMapped(partIndex: Int): Boolean = partitions.getPartData( partIndex, fragmentSpec.missing_value ).isMapped
  def mask: Option[CDByteArray] = maskOpt
  def shape: List[Int] = partitions.getShape.toList
  def getValue(partIndex: Int, indices: Array[Int] ): Float = data(partIndex).getValue( indices )

  override def toString = { "{Fragment: shape = [%s], section = [%s]}".format( partitions.getShape.mkString(","), fragmentSpec.roi.toString ) }

  def cutIntersection( partIndex: Int, cutSection: ma2.Section, copy: Boolean = true ): Option[DataFragment] = {
    val pFragSpec = partFragSpec( partIndex )
    pFragSpec.cutIntersection(cutSection) map { newFragSpec =>
        val newDataArray: CDFloatArray = data (partIndex).section (newFragSpec.roi.shiftOrigin (pFragSpec.roi).getRanges.toList)
        DataFragment ( newFragSpec, if (copy) newDataArray.dup () else newDataArray )
    }
  }
}

