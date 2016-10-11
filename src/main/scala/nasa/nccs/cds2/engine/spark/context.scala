package nasa.nccs.cds2.engine.spark

import nasa.nccs.caching.{CDASPartitioner, Partition}
import nasa.nccs.cdapi.cdm.{CDSVariable, OperationInput, OperationTransientInput, PartitionedFragment}
import nasa.nccs.cdapi.data.{HeapFltArray, RDDPartSpec, RDDPartition}
import nasa.nccs.cdapi.kernels.CDASExecutionContext
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.esgf.process._
import nasa.nccs.utilities.Loggable
import org.slf4j.LoggerFactory
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import ucar.ma2
import org.apache.log4j.Logger
import org.apache.log4j.Level

object CDSparkContext extends Loggable {
  val kyro_buffer_mb = 24
  val kyro_buffer_max_mb = 64
  val default_master = "local[%d]".format(CDASPartitioner.nProcessors)

  def apply( master: String=default_master, appName: String="CDAS", logConf: Boolean = false ) : CDSparkContext = {
    logger.info( "--------------------------------------------------------")
    logger.info( "   ****  NEW CDSparkContext Created  **** ")
    logger.info( "--------------------------------------------------------\n\n")
    val rv = new CDSparkContext(new SparkContext(getSparkConf(master, appName, logConf)))
    logger.info( "--------------------------------------------------------")
    logger.info( "   ****  CDSparkContext Creation FINISHED  **** ")
    logger.info( "--------------------------------------------------------")
    rv
  }
  def apply( conf: SparkConf ) : CDSparkContext = new CDSparkContext( new SparkContext(conf) )
  def apply( context: SparkContext ) : CDSparkContext = new CDSparkContext( context )
  def apply( url: String, name: String ) : CDSparkContext = new CDSparkContext( new SparkContext( getSparkConf( url, name, false ) ) )

  def getSparkConf( master: String, appName: String, logConf: Boolean  ) = new SparkConf(false)
    .setMaster( master )
    .setAppName( appName )
    .set("spark.logConf", logConf.toString )
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer") //
    .set("spark.kryoserializer.buffer",kyro_buffer_mb.toString)
    .set("spark.kryoserializer.buffer.max",kyro_buffer_max_mb.toString)
}

class CDSparkContext( @transient val sparkContext: SparkContext ) extends Loggable {
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("akka").setLevel(Level.OFF)

  def setLocalProperty(key: String, value: String): Unit = {
    sparkContext.setLocalProperty(key, value)
  }

  def getConf: SparkConf = sparkContext.getConf

  def cacheRDDPartition( partFrag: PartitionedFragment ): RDD[RDDPartition] = {
    val nPart = partFrag.partitions.parts.length
    val indexRDD: RDD[Int] = sparkContext.makeRDD( 0 to nPart-1, nPart )
    indexRDD.map( iPart => partFrag.partRDDPartition( iPart ) )
  }

  def domainRDDPartition( opInputs: List[OperationInput], context: CDASExecutionContext): RDD[RDDPartition] = {
    val opSection: Option[ma2.Section] = context.getOpSectionIntersection
    opInputs.headOption match {
      case  Some(pFrag: PartitionedFragment) =>
        val partFrags = opInputs.asInstanceOf[ List[PartitionedFragment]]
        val parts = partFrags.head.partitions.parts
        val partSpecs: Array[ RDDPartSpec ] = parts.map( partition => RDDPartSpec( partition, partFrags.map(pFrag => pFrag.getRDDVariableSpec(partition, opSection) ) ) )
        sparkContext.parallelize(partSpecs).map( _.getRDDPartition )
      case Some(tVar: OperationTransientInput) =>
        val transVars = opInputs.asInstanceOf[ List[OperationTransientInput]]
        val nparts = 3 // FIXME
        val part = transVars.head.variable.result
        sparkContext.parallelize( (0 until nparts) ).map( ip => part )
      case _ => throw new Exception( "Error, can't create RDD")
    }
  }
}


