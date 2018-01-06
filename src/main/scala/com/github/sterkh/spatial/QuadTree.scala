package com.github.sterkh.spatial

import java.io.{BufferedReader, FileReader}

import com.opencsv.CSVReader
import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.io.{WKTReader, WKTWriter}
import com.vividsolutions.jts.io.ParseException



case class Shape(id: String, geometry: Geometry) extends Serializable

class Node(extent: Geometry, depth: Int = 8, level: Int = 0, id: Int = 0) extends Serializable {

  var northWest: Node = _
  var northEast: Node = _
  var southWest: Node = _
  var southEast: Node = _

  var ids = Set.empty[Shape]

//  println(id)
  private val maxLevel = if (depth > QT_NODE_MAX_LEVEL) QT_NODE_MAX_LEVEL else depth

  private def toPolygon(env: Envelope): Polygon = {

    val coordinates = Array(
      new Coordinate(env.getMinX, env.getMinY),
      new Coordinate(env.getMinX, env.getMaxY),
      new Coordinate(env.getMaxX, env.getMaxY),
      new Coordinate(env.getMaxX, env.getMinY),
      new Coordinate(env.getMinX, env.getMinY)
    )

    val fact = new GeometryFactory()
    val linear = new GeometryFactory().createLinearRing(coordinates)

    new Polygon(linear, null, fact)
  }

  private def toWKT(geometry: Geometry): String = {

    val wkt = new WKTWriter()

    wkt.write(geometry)
  }

  private def addShape(shape: Shape){
    ids = ids + shape
//    if (level == 5 && nodeIdtoString(id) == "13322")
//      println(level, id, nodeIdtoString(id), ids)
  }

  private def subdivide(): Boolean = {

    val env = extent.getEnvelopeInternal
    val cx = env.centre.x
    val cy = env.centre.y
    val dw = env.getWidth / 2.0
    val dh = env.getHeight / 2.0

    northWest = new Node(toPolygon(new Envelope(cx - dw, cx, cy, cy + dh)), maxLevel, level + 1, (id << 2) + 0)
    northEast = new Node(toPolygon(new Envelope(cx, cx + dw, cy, cy + dh)), maxLevel, level + 1, (id << 2) + 1)
    southWest = new Node(toPolygon(new Envelope(cx - dw, cx, cy - dh, cy)), maxLevel, level + 1, (id << 2) + 2)
    southEast = new Node(toPolygon(new Envelope(cx, cx + dw, cy - dh, cy)), maxLevel, level + 1, (id << 2) + 3)

    true
  }

//  def queryNode(nodeId: Int): Set[String] = {
//
//    if (nodeId > 0 && northWest != null) {
//      nodeId & 0x03 match {
//        case 0x00 => northWest.queryNode(nodeId >> 2)
//        case 0x01 => northEast.queryNode(nodeId >> 2)
//        case 0x02 => southWest.queryNode(nodeId >> 2)
//        case 0x03 => southEast.queryNode(nodeId >> 2)
//      }
//    } else ids
//  }

  def queryNode(nodeId: String): Set[String] = {

      if (nodeId.nonEmpty  && northWest != null) {
        val quad = nodeId.head.toInt
        quad & 0x03 match {
          case 0x00 => northWest.queryNode(nodeId.tail)
          case 0x01 => northEast.queryNode(nodeId.tail)
          case 0x02 => southWest.queryNode(nodeId.tail)
          case 0x03 => southEast.queryNode(nodeId.tail)
        }
      } else ids.map(_.id)
  }

  def queryNode(nodeId: Int): Set[String] = {
    queryNode(nodeIdtoString(nodeId))
  }

  def countNodes(node: Node, leavesOnly: Boolean = false): Int = {
    var numNodes = 0

    if (node.northWest != null) {
      if (!leavesOnly)
        numNodes += 1
      numNodes += countNodes(node.northWest, leavesOnly)
      numNodes += countNodes(node.northEast, leavesOnly)
      numNodes += countNodes(node.southWest, leavesOnly)
      numNodes += countNodes(node.southEast, leavesOnly)
    } else {
      numNodes += 1
    }

    numNodes
  }
  
  def nodeIdtoString(nodeId: Int): String = {
    var idStr: String = ""

    var id = nodeId

    while (id > 0){
      idStr = idStr + (id & 0x03).toString
      id = id >> 2
    }

    idStr.reverse
  }

  def nodeIdfromString(str: String): Int = {
    var nodeId: Int = 0
    str.reverse.foreach(c => (nodeId << 2) + c.toInt )
    nodeId
  }

  def insert(shape: Shape): Unit = {

    val geom = shape.geometry

//    if (level == 5 && nodeIdtoString(id) == "13322")
//      println(level, id, nodeIdtoString(id), ids, extent, extent.intersection(geom))

    if (level <= maxLevel) {

      if (geom.contains(extent)) {
        addShape(shape)
//        println(nodeIdtoString(id),"contains")
        return
      }

      val intersection = geom.intersection(extent)

      if (!intersection.isEmpty) {
        val intShape = Shape(shape.id, intersection)

        addShape(shape)

        if (level < maxLevel) {

          if (northWest == null) {
            subdivide()
          }

          northWest.insert(intShape)
          northEast.insert(intShape)
          southWest.insert(intShape)
          southEast.insert(intShape)
        }
      }
    }
  }

  private def queryPoint(point: Point): Set[Shape] = {

    var result = Set.empty[Shape]

    // если есть попадание в текущий квандрант
    if (extent.getEnvelopeInternal.contains(point.getX, point.getY)){
      // добавить в результирующий набор все ID из текущего квадранта
      result = ids
//      println(nodeIdtoString(id), id, level, ids)

      // и проверить дочерние квадранты, если они есть
      if (northWest != null){
        result = northWest.queryPoint(point) ++
          northEast.queryPoint(point) ++
          southWest.queryPoint(point) ++
          southEast.queryPoint(point)
      } else {
        // если дочерних нет, уточнить результат при необходимости
        if (result.size > 1){
          //println("refine", nodeIdtoString(id))
          result = result.filter(s => s.geometry.contains(point))
        }
      }
    }

    result
  }

  private def queryPoint(x: Double, y: Double): Set[Shape] = {

    val point = new GeometryFactory().createPoint(new Coordinate(x, y))
    queryPoint(point)
  }

  def query(x: Double, y: Double): Set[String] = {

    queryPoint(x, y).map(_.id)
  }

  def query(point: Point): Set[String] = {

    queryPoint(point).map(_.id)
  }
}

class QuadTree() extends Serializable {

  var extent: Geometry = _
  var shapes = List.empty[Shape]
  var index: Node = _

  var attributes: Map[String, Map[Int, String]] = _

  def createIndex(shapeList: List[Shape], depth: Int): Node = {

    shapes = shapeList

    val geomFactory = new GeometryFactory()
    val geomCollection = geomFactory.createGeometryCollection(shapes.map(_.geometry).toArray)

    extent = geomCollection.getEnvelope
    index = new Node(extent, depth)

    shapes.foreach(shape => {
      index.insert(shape)
    })

    index
  }

  def createIndex(csvFilePath: String, idColumn: Int, wktColumn: Int, depth: Int, attrs: List[Int] = List.empty[Int], separator: Char = ',', quoteChar: Char = '\"', fromLine: Int = 1): Node = {

    import collection.JavaConverters._

    val bufReader = new BufferedReader(new FileReader(csvFilePath))

    val csvReader = new CSVReader(bufReader, separator, quoteChar, fromLine)

    val lines: List[Array[String]] = csvReader.readAll().asScala.toList

    val wkt = new WKTReader()

    val shapeList = lines.map(line => {
      Shape(line(idColumn), wkt.read(line(wktColumn)))
    })

    val lineBuf = scala.collection.mutable.Map[String, Map[Int, String]]()

    // read attributes
    if (attrs.nonEmpty) {
      lines.foreach(line => {
        val id = line(idColumn)
        val attrBuf = scala.collection.mutable.Map[Int, String]()
        attrs.foreach(attr => {
          attrBuf(attr) = line(attr)
        })
        lineBuf(id) = attrBuf.toMap
      })
      attributes = lineBuf.toMap
    }

    createIndex(shapeList, depth)
  }
  
  def getIndex: Node = {
    index
  }

  def getAttr(id: String, attr: Int): String = {
    var res = ""
    
    if (attributes != null) {
      val map = attributes.getOrElse(id, Map.empty[Int, String])
      res = map.getOrElse(attr, "")
    }
    
    res
  }

  def writeIndex(filePath: String): Unit = {

    import java.io.FileOutputStream
    import java.io.ObjectOutputStream
    val out = new ObjectOutputStream(new FileOutputStream(filePath))
    out.writeObject(index)
  }

  def readIndex(filePath: String): Unit = {

    import java.io.FileInputStream
    import java.io.ObjectInputStream
    val in = new ObjectInputStream(new FileInputStream(filePath))

    index = in.readObject().asInstanceOf[Node]
  }

}
