package org.enso.languageserver.websocket.binary.factory

import java.util.UUID

import com.google.flatbuffers.FlatBufferBuilder
import org.enso.languageserver.protocol.binary.Path
import org.enso.languageserver.protocol.binary.factory.EnsoUuidFactory

object PathFactory {

  def create(rootId: UUID, segments: Seq[String])(
    implicit builder: FlatBufferBuilder
  ): Int = {
    val segmentOffsets =
      segments.foldRight(List.empty[Int]) {
        case (segment, tail) =>
          val offset = builder.createString(segment)
          offset :: tail
      }
    val segmentsOffset =
      Path.createSegmentsVector(builder, segmentOffsets.toArray)
    Path.startPath(builder)
    val uuid = EnsoUuidFactory.create(rootId)
    Path.addRootId(builder, uuid)
    Path.addSegments(builder, segmentsOffset)
    Path.endPath(builder)
  }

}
