// Copyright 2014,2015,2016,2017,2018,2019 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package commbank.grimlock.framework.encoding2

import java.util.Date

import scala.util.Try
import scala.util.matching.Regex

trait Schema[T] {
  /** Custom convertors (i.e. other than identity) for converting `T` to another type. */
  def converters: Set[Schema.Converter[T, Any]]

  /** An optional date type class for this data type. */
  def date: Option[T => Date]

  /** An optional numeric type class for this data type. */
  def numeric: Option[Numeric[T]]

  /** An optional intergal type class for this data type. */
  def integral: Option[Integral[T]]

  /** An ordering for this data type. */
  def ordering: Ordering[T]

  /**
   * Box a (basic) data type in a `Value`.
   *
   * @param value The data to box.
   *
   * @return The value wrapped in a `Value`.
   */
  def boxUnsafe(value: T): Value[T]

  def box(value: T): Option[Value[T]] = Try(boxUnsafe(value)).toOption

  /**
   * Compare two values.
   *
   * @param x The first value to compare.
   * @param y The second value to compare.
   *
   * @return The returned value is < 0 iff x < y, 0 iff x = y, > 0 iff x > y.
   */
  def compare(x: T, y: T): Int = ordering.compare(x, y)

  /**
   * Decode a basic data type.
   *
   * @param str String to decode.
   *
   * @return `Some[T]` if the decode was successful, `None` otherwise.
   */
  def decode(str: String): Option[T] = for {
    v <- parse(str)
    if (validate(v))
  } yield v

  /**
   * Converts a value to a consise (terse) string.
   *
   * @param value The value to convert to string.
   *
   * @return Short string representation of the value.
   */
  def encode(value: T): String

  /** Return a consise (terse) string representation of a schema. */
  def toShortString: String = name + round(paramString)

  /**
   * Validates if a value confirms to this schema.
   *
   * @param value The value to validate.
   *
   * @return True is the value confirms to this schema, false otherwise.
   */
  def validate(value: T): Boolean

  protected def name: String

  protected def paramString: String = ""

  protected def parse(str: String): Option[T]

  private def round(str: String): String = if (str.isEmpty) str else "(" + str + ")"
}

object Schema {
  type Converter[T, X] = (T) => X
}

/** Trait for schemas for numerical variables. */
trait RangeSchema[T] extends Schema[T] {
  val min: Option[T]
  val max: Option[T]

  protected def validateRange(
    value: T
  ): Boolean = min.fold(true)(t => ordering.gteq(t, value)) && max.fold(true)(t => ordering.lteq(t, value))
}

trait ScaleSchema[T] extends RangeSchema[T] {
  val date: Option[T => Date] = None
  val integral: Option[Integral[T]] = None
  val min: Option[T]
  val max: Option[T]
  val numeric: Option[Numeric[T]] = Option(num)
  val precision: Option[Int]
  val scale: Option[Int]

  def ordering: Ordering[T] = num

  def validate(value: T): Boolean = {
    val dec = toDecimal(value)

    validateRange(value) &&
      precision.fold(true)(p => dec.precision <= p) &&
      scale.fold(true)(s => dec.scale <= s)
  }

  protected implicit val num: Numeric[T]

  override protected def paramString: String = SchemaParameters.writeRange(min, max, this, extra)

  protected def toDecimal(value: T): BigDecimal = BigDecimal.double2bigDecimal(num.toDouble(value))

  private val extra = List(("precision", precision.map(_.toString)), ("scale", scale.map(_.toString)))
}

/** Schema for dealing with `BigDecimal`. */
case class DecimalSchema(
  min: Option[BigDecimal],
  max: Option[BigDecimal],
  decimalPrecision: Int,
  decimalScale: Int
) extends ScaleSchema[BigDecimal] {
  val converters: Set[Schema.Converter[BigDecimal, Any]] = decimalAsDoubleConvertor.toSet ++
    decimalAsFloatConvertor.toSet
  val precision: Option[Int] = Option(decimalPrecision)
  val scale: Option[Int] = Option(decimalScale)

  def boxUnsafe(value: BigDecimal): Value[BigDecimal] = DecimalValue(value, this)

  def encode(value: BigDecimal): String = value.toString

  protected implicit val num: Numeric[BigDecimal] = implicitly[Numeric[BigDecimal]]

  protected def name: String = "decimal"

  protected def parse(str: String): Option[BigDecimal] = Try(BigDecimal(str)).toOption

  override protected def toDecimal(value: BigDecimal): BigDecimal = value

  private def decimalAsDoubleConvertor: Option[Schema.Converter[BigDecimal, Double]] = {
    if ((decimalPrecision - decimalScale) >= 309)
      None
    else
      Option(DecimalSchema.BigDecimalAsDouble)
  }

  private def decimalAsFloatConvertor: Option[Schema.Converter[BigDecimal, Float]] = {
    if ((decimalPrecision - decimalScale) >= 39)
      None
    else
      Option(DecimalSchema.BigDecimalAsFloat)
  }
}

object DecimalSchema {
  private case object BigDecimalAsDouble extends Schema.Converter[BigDecimal, Double] {
    def apply(i: BigDecimal): Double = i.doubleValue()
  }

  private case object BigDecimalAsFloat extends Schema.Converter[BigDecimal, Float] {
    def apply(i: BigDecimal): Float = i.floatValue()
  }
}

/** Schema for dealing with `Double`. */
case class DoubleSchema(
  min: Option[Double],
  max: Option[Double],
  precision: Option[Int],
  scale: Option[Int]
) extends ScaleSchema[Double] {
  val converters: Set[Schema.Converter[Double, Any]] = Set.empty

  def boxUnsafe(value: Double): Value[Double] = DoubleValue(value, this)

  def encode(value: Double): String = value.toString

  protected implicit val num: Numeric[Double] = implicitly[Numeric[Double]]

  protected def name: String = "double"

  protected def parse(str: String): Option[Double] = Try(str.toDouble).toOption
}

/** Trait for schemas for categorical variables. */
trait DomainSchema[T] extends Schema[T] {
  /** Values the variable can take. */
  val domain: Either[Set[T], Regex]

  def validate(value: T): Boolean = domain
    .fold(set => set.isEmpty || set.contains(value), regex => regex.pattern.matcher(encode(value)).matches)
}

trait StringSchema extends Schema[String] {
  val converters: Set[Schema.Converter[String, Any]] = Set.empty

  def boxUnsafe(value: String): Value[String] = StringValue(value, this)

  def date: Option[String => Date] = None

  def encode(value: String): String = value

  def integral: Option[Integral[String]] = None

  def numeric: Option[Numeric[String]] = None

  protected def parse(str: String): Option[String] = Option(str)
}

case class DomainStringSchema(
  domain: Either[Set[String], Regex],
  customOrdering: Option[Ordering[String]] = None
) extends DomainSchema[String] with StringSchema {
  def ordering: Ordering[String] = customOrdering.getOrElse(Ordering.String)

  protected def name: String = "domainString"
}

case class BoundedStringSchema(
  min: Int,
  max: Int
) extends StringSchema {
  def ordering: Ordering[String] = Ordering.String

  def validate(value: String): Boolean = value.size >= min && value.size <= max

  protected def name: String = "boundedString"
}

/** Functions for dealing with schema parameters. */
private object SchemaParameters {
  def writeRange[T](
    min: Option[T],
    max: Option[T],
    schema: Schema[T],
    extra: List[(String, Option[String])]
  ): String = {
    val list = List(
      min.map(t => s"min=${schema.encode(t)}"),
      max.map(t => s"max=${schema.encode(t)}")
    ) ++ extra.map { case (name, value) => value.map(v => s"${name}=${v}") }

    list.flatten.mkString(",")
  }
}

