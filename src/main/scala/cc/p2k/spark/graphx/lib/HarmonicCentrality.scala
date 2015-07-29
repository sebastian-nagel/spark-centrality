package cc.p2k.spark.graphx.lib

import org.apache.spark.graphx.lib.ShortestPaths
import org.apache.spark.Logging
import org.apache.spark.graphx._
import collection.mutable
import scala.language.postfixOps
import com.twitter.algebird._


object HarmonicCentrality extends Logging {

  /** маппинг соседей по расстоянию */
  type NMap = mutable.HashMap[Int, HLL]

  /**
   * Harmonic Centrality for node
   * @param vertexId VertexId
   * @param graph Graph
   * @return
   */
  def personalizedHarmonicCentrality(vertexId: VertexId, graph: Graph[Double, Double]): Double ={
    val initialGraph = graph.mapVertices(
      (id, _) => {
        if (id == vertexId) 0.0
        else Double.PositiveInfinity
      }
    )

    val vp = (id: VertexId, dist: Double, newDist:Double) => math.min(dist, newDist)

    def mergeMsg(a: Double, b: Double) = math.min(a,b)

    def sendMsg(triplet: EdgeTriplet[Double, Double]) = {
      if (triplet.srcAttr + triplet.attr < triplet.dstAttr) {
        Iterator((triplet.dstId, triplet.srcAttr + triplet.attr))
      } else {
        Iterator.empty
      }
    }

    val paths = initialGraph.pregel(Double.PositiveInfinity)(vp, sendMsg, mergeMsg)

    val hr = paths.vertices
      .map[Double](
        tuple => {
          if (tuple._1 == vertexId) 0
          else 1 / tuple._2
        }
      )
      .reduce( (a, b) => a + b )

    hr
  }

  /**
   * @param graph Graph
   * @param maxDistance Int
   * @return
   */
  def harmonicCentrality(graph: Graph[Double, Int], maxDistance: Int = 6): Graph[NMap, Int] = {

    val BIT_SIZE = 12

    val hll = new HyperLogLogMonoid(BIT_SIZE)

    val initGraph: Graph[NMap, Int] = graph.mapVertices(
      (id: VertexId, v: Double) => new mutable.HashMap[Int, HLL]()
    )

    val initMessage = new mutable.HashMap[Int, HLL]() {
      override def default(key:Int) = new HyperLogLogMonoid(BIT_SIZE).zero
    }

    def incrementNMap(p: NMap): NMap = p.map {
      case (v, d) if v < maxDistance => (v + 1) -> d
    }

    /**
     * @param x VertexId
     * @param vertexValue Double VD
     * @param message Double message type
     * @return VD
     */
    def vertexProgram(x: VertexId, vertexValue: NMap, message: NMap) = {
      for ((distance, _hll) <- message) {
        val op = vertexValue.get(distance)
        if (op.isEmpty){
          vertexValue(distance) = new HyperLogLogMonoid(BIT_SIZE).zero
        }
        vertexValue(distance) += _hll
      }
      vertexValue
    }

    /**
     * @param edge EdgeTriplet  EdgeTriplet[VD, ED]
     * @return Iterator[(VertexId, A)]
     */
    def sendMessage(edge: EdgeTriplet[NMap, Int]): Iterator[(VertexId, NMap)] ={
      val newAttr = incrementNMap(edge.dstAttr)
      if (edge.srcAttr != newAttr) {
        Iterator((edge.dstId, newAttr))
      } else {
        Iterator.empty
      }
    }

    /**
     * @param a Double VD
     * @param b Double VD
     * @return VD
     */
    def messageCombiner(a: NMap, b: NMap): NMap = a

    Pregel(initGraph, initMessage, activeDirection = EdgeDirection.In)(
      vertexProgram, sendMessage, messageCombiner)
  }

}
