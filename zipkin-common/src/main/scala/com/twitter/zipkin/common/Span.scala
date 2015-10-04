/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.twitter.zipkin.common

import com.twitter.util.NonFatal
import com.twitter.zipkin.Constants
import scala.collection.breakOut

/**
 * A span represents one RPC request. A trace is made up of many spans.
 *
 * A span can contain multiple annotations, some are always included such as
 * Client send -> Server received -> Server send -> Client receive.
 *
 * Some are created by users, describing application specific information,
 * such as cache hits/misses.
 */
object Span {
  // TODO(jeff): what?!
  def apply(span: Span): Span = span

  def apply(
    _traceId: Long,
    _name: String,
    _id: Long,
    _parentId: Option[Long],
    _annotations: List[Annotation],
    _binaryAnnotations: Seq[BinaryAnnotation],
    _debug: Boolean = false
  ): Span = new Span {
    def traceId = _traceId
    def name = _name
    def id = _id
    def parentId = _parentId
    def annotations = _annotations.sorted
    def binaryAnnotations = _binaryAnnotations
    def debug = _debug
  }

  def unapply(span: Span): Option[(Long, String, Long, Option[Long], List[Annotation], Seq[BinaryAnnotation], Boolean)] =
    try {
      Some(
        span.traceId,
        span.name,
        span.id,
        span.parentId,
        span.annotations,
        span.binaryAnnotations,
        span.debug
      )
    } catch {
      case NonFatal(_) => None
    }
}

/**
 * @param traceId random long that identifies the trace, will be set in all spans in this trace
 * @param name name of span, can be rpc method name for example
 * @param id random long that identifies this span
 * @param parentId reference to the parent span in the trace tree
 * @param annotations annotations, containing a timestamp and some value. both user generated and
 * some fixed ones from the tracing framework. Sorted ascending by timestamp
 * @param binaryAnnotations  binary annotations, can contain more detailed information such as
 * serialized objects. Sorted ascending by timestamp. Sorted ascending by timestamp
 * @param debug if this is set we will make sure this span is stored, no matter what the samplers want
 */
trait Span extends Ordered[Span] { self =>
  def traceId: Long
  def name: String
  def id: Long
  def parentId: Option[Long]
  def annotations: List[Annotation]
  def binaryAnnotations: Seq[BinaryAnnotation]
  def debug: Boolean

  // TODO: cache first timestamp when this is a normal case class as opposed to a trait
  override def compare(that: Span) =
    java.lang.Long.compare(startTs.getOrElse(0L), that.startTs.getOrElse(0L))

  def copy(
    traceId: Long = self.traceId,
    name: String = self.name,
    id: Long = self.id,
    parentId: Option[Long] = self.parentId,
    annotations: List[Annotation] = self.annotations,
    binaryAnnotations: Seq[BinaryAnnotation] = self.binaryAnnotations,
    debug: Boolean = self.debug
  ): Span = Span(traceId, name, id, parentId, annotations, binaryAnnotations, debug)

  private def tuple = (traceId, name, id, parentId, annotations, binaryAnnotations, debug)

  override def equals(other: Any): Boolean = other match {
    case o: Span => o.tuple == self.tuple
    case _ => false
  }

  override def hashCode: Int = tuple.hashCode

  override def toString: String = s"Span${tuple}"

  def serviceNames: Set[String] =
    annotations.flatMap(a => a.host.map(h => h.serviceName.toLowerCase)).toSet

  /**
   * Tries to extract the best possible service name
   */
  def serviceName: Option[String] = {
    if (annotations.isEmpty) None else {
      serverSideAnnotations.flatMap(_.host).headOption.map(_.serviceName) orElse {
        clientSideAnnotations.flatMap(_.host).headOption.map(_.serviceName)
      }
    }
  }

  /**
   * Iterate through list of annotations and return the one with the given value.
   */
  def getAnnotation(value: String): Option[Annotation] =
    annotations.find(_.value == value)

  /**
   * Iterate through list of binaryAnnotations and return the one with the given key.
   */
  def getBinaryAnnotation(key: String): Option[BinaryAnnotation] =
    binaryAnnotations.find(_.key == key)

  /**
   * Take two spans with the same span id and merge all data into one of them.
   */
  def mergeSpan(mergeFrom: Span): Span = {
    if (id != mergeFrom.id) {
      throw new IllegalArgumentException("Span ids must match")
    }

    // ruby tracing can give us an empty name in one part of the span
    val selectedName = name match {
      case "" => mergeFrom.name
      case "Unknown" => mergeFrom.name
      case _ => name
    }

    new Span {
      def traceId = self.traceId
      def name = selectedName
      def id = self.id
      def parentId = self.parentId
      def annotations = (self.annotations ++ mergeFrom.annotations).sorted
      def binaryAnnotations = self.binaryAnnotations ++ mergeFrom.binaryAnnotations
      def debug = self.debug | mergeFrom.debug
    }
  }

  /**
   * Get the first annotation by timestamp.
   */
  def firstAnnotation: Option[Annotation] = annotations.headOption

  /**
   * Get the last annotation by timestamp.
   */
  def lastAnnotation: Option[Annotation] = annotations.lastOption

  /**
   * Endpoints involved in this span
   */
  def endpoints: Set[Endpoint] =
    annotations.flatMap(_.host).toSet

  /**
   * Endpoint that is likely the owner of this span
   */
  def clientSideEndpoint: Option[Endpoint] =
    clientSideAnnotations.map(_.host).flatten.headOption

  /**
   * Pick out the core client side annotations
   */
  def clientSideAnnotations: Seq[Annotation] =
    annotations.filter(a => Constants.CoreClient.contains(a.value))

  /**
   * Pick out the core server side annotations
   */
  def serverSideAnnotations: Seq[Annotation] =
    annotations.filter(a => Constants.CoreServer.contains(a.value))

  /**
   * Duration of this span. May be None if we cannot find any annotations.
   */
  def duration: Option[Long] =
    for (first <- firstAnnotation; last <- lastAnnotation)
      yield last.timestamp - first.timestamp

  /**
   * @return true  if Span contains at most one of each core annotation
   *         false otherwise
   */
  def isValid: Boolean = {
    Constants.CoreAnnotations.map { c =>
      annotations.filter(_.value == c).length > 1
    }.count(b => b) == 0
  }

  /**
   * Get the annotations as a map with value to annotation bindings.
   */
  def getAnnotationsAsMap(): Map[String, Annotation] =
    annotations.map(a => a.value -> a)(breakOut)

  def endTs: Option[Long] = lastAnnotation.map(_.timestamp)
  def startTs: Option[Long] = firstAnnotation.map(_.timestamp)
}
