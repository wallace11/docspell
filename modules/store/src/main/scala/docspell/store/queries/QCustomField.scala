/*
 * Copyright 2020 Docspell Contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package docspell.store.queries

import cats.data.{NonEmptyList => Nel}
import cats.implicits._

import docspell.common._
import docspell.store.qb.DSL._
import docspell.store.qb._
import docspell.store.records._

import doobie._

object QCustomField {
  private val f = RCustomField.as("f")
  private val v = RCustomFieldValue.as("v")

  final case class CustomFieldData(field: RCustomField, usageCount: Int)

  def findAllLike(
      coll: Ident,
      nameQuery: Option[String]
  ): ConnectionIO[Vector[CustomFieldData]] =
    findFragment(coll, nameQuery, None).build.query[CustomFieldData].to[Vector]

  def findById(field: Ident, collective: Ident): ConnectionIO[Option[CustomFieldData]] =
    findFragment(collective, None, field.some).build.query[CustomFieldData].option

  private def findFragment(
      coll: Ident,
      nameQuery: Option[String],
      fieldId: Option[Ident]
  ): Select = {
    val nameFilter = nameQuery.map { q =>
      f.name.likes(q) || (f.label.isNotNull && f.label.like(q))
    }

    Select(
      f.all.map(_.s).append(count(v.id).as("num")),
      from(f)
        .leftJoin(v, f.id === v.field),
      f.cid === coll &&? nameFilter &&? fieldId.map(fid => f.id === fid),
      GroupBy(f.all)
    )
  }

  final case class FieldValue(
      field: RCustomField,
      itemId: Ident,
      value: String
  )

  def findAllValues(itemIds: Nel[Ident]): ConnectionIO[List[FieldValue]] = {
    val cf = RCustomField.as("cf")
    val cv = RCustomFieldValue.as("cv")

    run(
      select(cf.all, Nel.of(cv.itemId, cv.value)),
      from(cv).innerJoin(cf, cv.field === cf.id),
      cv.itemId.in(itemIds)
    )
      .query[FieldValue]
      .to[List]
  }

}
