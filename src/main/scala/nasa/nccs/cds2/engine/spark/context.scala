package nasa.nccs.cds2.engine.spark

import nasa.nccs.caching.Partition
import nasa.nccs.cdapi.cdm.{CDSVariable, PartitionedFragment}
import nasa.nccs.cdapi.data.{HeapFltArray, RDDPartSpec, RDDPartition}
import nasa.nccs.cdapi.kernels.CDASExecutionContext
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.esgf.process._
import org.slf4j.LoggerFactory
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import ucar.ma2

object CDSparkContext {
  val kyro_buffer_mb = 24
  val kyro_buffer_max_mb = 64

  def apply() : CDSparkContext = new CDSparkContext( new SparkContext( getSparkConf() ) )
  def apply( conf: SparkConf ) : CDSparkContext = new CDSparkContext( new SparkContext(conf) )
  def apply( context: SparkContext ) : CDSparkContext = new CDSparkContext( context )
  def apply( url: String, name: String ) : CDSparkContext = new CDSparkContext( new SparkContext( getSparkConf(url,name) ) )

  def getSparkConf( master: String="local[*]", appName: String="CDAS", logConf: Boolean = true ) = new SparkConf(false)
    .setMaster( master )
    .setAppName( appName )
    .set("spark.logConf", logConf.toString )
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .set("spark.kryoserializer.buffer.mb",kyro_buffer_mb.toString)
    .set("spark.kryoserializer.buffer.max.mb",kyro_buffer_max_mb.toString)
}

class CDSparkContext( @transient val sparkContext: SparkContext ) {

  val logger = LoggerFactory.getLogger(this.getClass)

  def setLocalProperty(key: String, value: String): Unit = {
    sparkContext.setLocalProperty(key, value)
  }

  def getConf: SparkConf = sparkContext.getConf

  def cacheRDDPartition( partFrag: PartitionedFragment ): RDD[RDDPartition] = {
    val nPart = partFrag.partitions.parts.length
    val indexRDD: RDD[Int] = sparkContext.makeRDD( 0 to nPart-1, nPart )
    indexRDD.map( iPart => partFrag.partRDDPartition( iPart ) )
  }

  def domainRDDPartition(partFrags: List[PartitionedFragment], context: CDASExecutionContext): RDD[RDDPartition] = {
    val parts = partFrags.head.partitions.parts
    val opSection: Option[ma2.Section] = context.getOpSectionIntersection
    val partSpecs: Array[ RDDPartSpec ] = parts.map( partition => RDDPartSpec( partition, partFrags.map(pFrag => pFrag.getRDDVariableSpec(partition, opSection) ) ) )
    sparkContext.parallelize(partSpecs).map( _.getRDDPartition )
  }
}


