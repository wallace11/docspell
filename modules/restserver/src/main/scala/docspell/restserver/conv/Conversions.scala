/*
 * Copyright 2020 Docspell Contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package docspell.restserver.conv

import java.time.{LocalDate, ZoneId}

import cats.effect.{Async, Sync}
import cats.implicits._
import fs2.Stream

import docspell.backend.ops.OCollective.{InsightData, PassChangeResult}
import docspell.backend.ops.OCustomFields.SetValueResult
import docspell.backend.ops.OJob.JobCancelResult
import docspell.backend.ops.OUpload.{UploadData, UploadMeta, UploadResult}
import docspell.backend.ops._
import docspell.common._
import docspell.common.syntax.all._
import docspell.ftsclient.FtsResult
import docspell.restapi.model._
import docspell.restserver.conv.Conversions._
import docspell.store.queries.{AttachmentLight => QAttachmentLight}
import docspell.store.records._
import docspell.store.{AddResult, UpdateResult}

import bitpeace.FileMeta
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.Multipart
import org.log4s.Logger

trait Conversions {

  def mkSearchStats(sum: OItemSearch.SearchSummary): SearchStats =
    SearchStats(
      sum.count,
      mkTagCloud(sum.tags),
      mkTagCategoryCloud(sum.cats),
      sum.fields.map(mkFieldStats),
      sum.folders.map(mkFolderStats)
    )

  def mkFolderStats(fs: docspell.store.queries.FolderCount): FolderStats =
    FolderStats(fs.id, fs.name, mkIdName(fs.owner), fs.count)

  def mkFieldStats(fs: docspell.store.queries.FieldStats): FieldStats =
    FieldStats(
      fs.field.id,
      fs.field.name,
      fs.field.label,
      fs.field.ftype,
      fs.count,
      fs.sum.doubleValue,
      fs.avg.doubleValue,
      fs.max.doubleValue,
      fs.min.doubleValue
    )

  // insights
  def mkItemInsights(d: InsightData): ItemInsights =
    ItemInsights(
      d.incoming,
      d.outgoing,
      d.deleted,
      d.bytes,
      mkTagCloud(d.tags)
    )

  def mkTagCloud(tags: List[OCollective.TagCount]) =
    TagCloud(tags.map(tc => TagCount(mkTag(tc.tag), tc.count)))

  def mkTagCategoryCloud(tags: List[OCollective.CategoryCount]) =
    NameCloud(tags.map(tc => NameCount(tc.category, tc.count)))

  // attachment meta
  def mkAttachmentMeta(rm: RAttachmentMeta): AttachmentMeta =
    AttachmentMeta(
      rm.content.getOrElse(""),
      rm.nerlabels.map(nl => Label(nl.tag, nl.label, nl.startPosition, nl.endPosition)),
      mkItemProposals(rm.proposals)
    )

  // item proposal
  def mkItemProposals(ml: MetaProposalList): ItemProposals = {
    def get(mpt: MetaProposalType) =
      ml.find(mpt).map(mp => mp.values.toList.map(_.ref).map(mkIdName)).getOrElse(Nil)
    def getDates(mpt: MetaProposalType): List[Timestamp] =
      ml.find(mpt)
        .map(mp =>
          mp.values.toList
            .map(cand => cand.ref.id.id)
            .flatMap(str => Either.catchNonFatal(LocalDate.parse(str)).toOption)
            .map(_.atTime(12, 0).atZone(ZoneId.of("GMT")))
            .map(zdt => Timestamp(zdt.toInstant))
        )
        .getOrElse(Nil)
        .distinct
        .take(5)

    ItemProposals(
      corrOrg = get(MetaProposalType.CorrOrg),
      corrPerson = get(MetaProposalType.CorrPerson),
      concPerson = get(MetaProposalType.ConcPerson),
      concEquipment = get(MetaProposalType.ConcEquip),
      itemDate = getDates(MetaProposalType.DocDate),
      dueDate = getDates(MetaProposalType.DueDate)
    )
  }

  // item detail
  def mkItemDetail(data: OItemSearch.ItemData): ItemDetail =
    ItemDetail(
      data.item.id,
      data.item.direction,
      data.item.name,
      data.item.source,
      data.item.state,
      data.item.created,
      data.item.updated,
      data.item.itemDate,
      data.corrOrg.map(o => IdName(o.oid, o.name)),
      data.corrPerson.map(p => IdName(p.pid, p.name)),
      data.concPerson.map(p => IdName(p.pid, p.name)),
      data.concEquip.map(e => IdName(e.eid, e.name)),
      data.inReplyTo.map(mkIdName),
      data.folder.map(mkIdName),
      data.item.dueDate,
      data.item.notes,
      data.attachments.map((mkAttachment(data) _).tupled).toList,
      data.sources.map((mkAttachmentSource _).tupled).toList,
      data.archives.map((mkAttachmentArchive _).tupled).toList,
      data.tags.map(mkTag).toList,
      data.customFields.map(mkItemFieldValue).toList
    )

  def mkItemFieldValue(v: OItemSearch.ItemFieldValue): ItemFieldValue =
    ItemFieldValue(v.fieldId, v.fieldName, v.fieldLabel, v.fieldType, v.value)

  def mkAttachment(
      item: OItemSearch.ItemData
  )(ra: RAttachment, m: FileMeta): Attachment = {
    val converted =
      item.sources.find(_._1.id == ra.id).exists(_._2.checksum != m.checksum)
    Attachment(ra.id, ra.name, m.length, MimeType.unsafe(m.mimetype.asString), converted)
  }

  def mkAttachmentSource(ra: RAttachmentSource, m: FileMeta): AttachmentSource =
    AttachmentSource(ra.id, ra.name, m.length, MimeType.unsafe(m.mimetype.asString))

  def mkAttachmentArchive(ra: RAttachmentArchive, m: FileMeta): AttachmentSource =
    AttachmentSource(ra.id, ra.name, m.length, MimeType.unsafe(m.mimetype.asString))

  // item list

  def mkCustomValue(v: CustomFieldValue): OItemSearch.CustomValue =
    OItemSearch.CustomValue(v.field, v.value)

  def mkItemList(v: Vector[OItemSearch.ListItem]): ItemLightList = {
    val groups = v.groupBy(item => item.date.toUtcDate.toString.substring(0, 7))

    def mkGroup(g: (String, Vector[OItemSearch.ListItem])): ItemLightGroup =
      ItemLightGroup(g._1, g._2.map(mkItemLight).toList)

    val gs =
      groups.map(mkGroup).toList.sortWith((g1, g2) => g1.name.compareTo(g2.name) >= 0)
    ItemLightList(gs)
  }

  def mkItemListFts(v: Vector[OFulltext.FtsItem]): ItemLightList = {
    val groups = v.groupBy(item => item.item.date.toUtcDate.toString.substring(0, 7))

    def mkGroup(g: (String, Vector[OFulltext.FtsItem])): ItemLightGroup =
      ItemLightGroup(g._1, g._2.map(mkItemLight).toList)

    val gs =
      groups.map(mkGroup _).toList.sortWith((g1, g2) => g1.name.compareTo(g2.name) >= 0)
    ItemLightList(gs)
  }

  def mkItemListWithTags(v: Vector[OItemSearch.ListItemWithTags]): ItemLightList = {
    val groups = v.groupBy(ti => ti.item.date.toUtcDate.toString.substring(0, 7))

    def mkGroup(g: (String, Vector[OItemSearch.ListItemWithTags])): ItemLightGroup =
      ItemLightGroup(g._1, g._2.map(mkItemLightWithTags).toList)

    val gs =
      groups.map(mkGroup _).toList.sortWith((g1, g2) => g1.name.compareTo(g2.name) >= 0)
    ItemLightList(gs)
  }

  def mkItemListWithTagsFts(v: Vector[OFulltext.FtsItemWithTags]): ItemLightList = {
    val groups = v.groupBy(ti => ti.item.item.date.toUtcDate.toString.substring(0, 7))

    def mkGroup(g: (String, Vector[OFulltext.FtsItemWithTags])): ItemLightGroup =
      ItemLightGroup(g._1, g._2.map(mkItemLightWithTags).toList)

    val gs =
      groups.map(mkGroup _).toList.sortWith((g1, g2) => g1.name.compareTo(g2.name) >= 0)
    ItemLightList(gs)
  }

  def mkItemListWithTagsFtsPlain(v: Vector[OFulltext.FtsItemWithTags]): ItemLightList =
    if (v.isEmpty) ItemLightList(Nil)
    else ItemLightList(List(ItemLightGroup("Results", v.map(mkItemLightWithTags).toList)))

  def mkItemListFtsPlain(v: Vector[OFulltext.FtsItem]): ItemLightList =
    if (v.isEmpty) ItemLightList(Nil)
    else ItemLightList(List(ItemLightGroup("Results", v.map(mkItemLight).toList)))

  def mkItemLight(i: OItemSearch.ListItem): ItemLight =
    ItemLight(
      i.id,
      i.name,
      i.state,
      i.date,
      i.dueDate,
      i.source,
      i.direction.name.some,
      i.corrOrg.map(mkIdName),
      i.corrPerson.map(mkIdName),
      i.concPerson.map(mkIdName),
      i.concEquip.map(mkIdName),
      i.folder.map(mkIdName),
      Nil, //attachments
      Nil, //tags
      Nil, //customfields
      i.notes,
      Nil // highlight
    )

  def mkItemLight(i: OFulltext.FtsItem): ItemLight = {
    val il        = mkItemLight(i.item)
    val highlight = mkHighlight(i.ftsData)
    il.copy(highlighting = highlight)
  }

  def mkItemLightWithTags(i: OItemSearch.ListItemWithTags): ItemLight =
    mkItemLight(i.item)
      .copy(
        tags = i.tags.map(mkTag),
        attachments = i.attachments.map(mkAttachmentLight),
        customfields = i.customfields.map(mkItemFieldValue)
      )

  private def mkAttachmentLight(qa: QAttachmentLight): AttachmentLight =
    AttachmentLight(qa.id, qa.position, qa.name, qa.pageCount)

  def mkItemLightWithTags(i: OFulltext.FtsItemWithTags): ItemLight = {
    val il        = mkItemLightWithTags(i.item)
    val highlight = mkHighlight(i.ftsData)
    il.copy(highlighting = highlight)
  }

  private def mkHighlight(ftsData: OFulltext.FtsData): List[HighlightEntry] =
    ftsData.items.filter(_.context.nonEmpty).sortBy(-_.score).map { fdi =>
      fdi.matchData match {
        case FtsResult.AttachmentData(_, aName) =>
          HighlightEntry(aName, fdi.context)
        case FtsResult.ItemData =>
          HighlightEntry("Item", fdi.context)
      }
    }

  // job
  def mkJobQueueState(state: OJob.CollectiveQueueState): JobQueueState = {
    def desc(f: JobDetail => Option[Timestamp])(j1: JobDetail, j2: JobDetail): Boolean = {
      val t1 = f(j1).getOrElse(Timestamp.Epoch)
      val t2 = f(j2).getOrElse(Timestamp.Epoch)
      t1.value.isAfter(t2.value)
    }
    def asc(f: JobDetail => Option[Timestamp])(j1: JobDetail, j2: JobDetail): Boolean = {
      val t1 = f(j1).getOrElse(Timestamp.Epoch)
      val t2 = f(j2).getOrElse(Timestamp.Epoch)
      t1.value.isBefore(t2.value)
    }
    JobQueueState(
      state.running.map(mkJobDetail).toList.sortWith(asc(_.started)),
      state.done.map(mkJobDetail).toList.sortWith(desc(_.finished)),
      state.queued.map(mkJobDetail).toList.sortWith(asc(_.submitted.some))
    )
  }

  def mkJobDetail(jd: OJob.JobDetail): JobDetail =
    JobDetail(
      jd.job.id,
      jd.job.subject,
      jd.job.submitted,
      jd.job.priority,
      jd.job.state,
      jd.job.retries,
      jd.logs.map(mkJobLog).toList,
      jd.job.progress,
      jd.job.worker,
      jd.job.started,
      jd.job.finished
    )

  def mkJobLog(jl: RJobLog): JobLogEvent =
    JobLogEvent(jl.created, jl.level, jl.message)

  // upload
  def readMultipart[F[_]: Async](
      mp: Multipart[F],
      sourceName: String,
      logger: Logger,
      prio: Priority,
      validFileTypes: Seq[MimeType]
  ): F[UploadData[F]] = {
    def parseMeta(body: Stream[F, Byte]): F[ItemUploadMeta] =
      body
        .through(fs2.text.utf8.decode)
        .parseJsonAs[ItemUploadMeta]
        .map(
          _.fold(
            ex => {
              logger.error(ex)("Reading upload metadata failed.")
              throw ex
            },
            identity
          )
        )

    val meta: F[(Boolean, UploadMeta)] = mp.parts
      .find(_.name.exists(_.equalsIgnoreCase("meta")))
      .map(p => parseMeta(p.body))
      .map(fm =>
        fm.map(m =>
          (
            m.multiple,
            UploadMeta(
              m.direction,
              sourceName,
              m.folder,
              validFileTypes,
              m.skipDuplicates.getOrElse(false),
              m.fileFilter.getOrElse(Glob.all),
              m.tags.map(_.items).getOrElse(Nil),
              m.language,
              m.attachmentsOnly
            )
          )
        )
      )
      .getOrElse(
        (
          true,
          UploadMeta(
            None,
            sourceName,
            None,
            validFileTypes,
            false,
            Glob.all,
            Nil,
            None,
            None
          )
        )
          .pure[F]
      )

    val files = mp.parts
      .filter(p => p.name.forall(s => !s.equalsIgnoreCase("meta")))
      .map(p =>
        OUpload
          .File(p.filename, p.headers.get[`Content-Type`].map(fromContentType), p.body)
      )
    for {
      metaData <- meta
      _        <- Async[F].delay(logger.debug(s"Parsed upload meta data: $metaData"))
      tracker  <- Ident.randomId[F]
    } yield UploadData(metaData._1, metaData._2, files, prio, Some(tracker))
  }

  // organization and person
  def mkOrg(v: OOrganization.OrgAndContacts): Organization = {
    val ro = v.org
    Organization(
      ro.oid,
      ro.name,
      Address(ro.street, ro.zip, ro.city, ro.country),
      v.contacts.map(mkContact).toList,
      ro.notes,
      ro.created,
      ro.shortName,
      ro.use
    )
  }

  def newOrg[F[_]: Sync](v: Organization, cid: Ident): F[OOrganization.OrgAndContacts] = {
    def contacts(oid: Ident) =
      v.contacts.traverse(c => newContact(c, oid.some, None))
    for {
      now  <- Timestamp.current[F]
      oid  <- Ident.randomId[F]
      cont <- contacts(oid)
      org = ROrganization(
        oid,
        cid,
        v.name.trim,
        v.address.street.trim,
        v.address.zip.trim,
        v.address.city.trim,
        v.address.country.trim,
        v.notes,
        now,
        now,
        v.shortName.map(_.trim),
        v.use
      )
    } yield OOrganization.OrgAndContacts(org, cont)
  }

  def changeOrg[F[_]: Sync](
      v: Organization,
      cid: Ident
  ): F[OOrganization.OrgAndContacts] = {
    def contacts(oid: Ident) =
      v.contacts.traverse(c => newContact(c, oid.some, None))
    for {
      now  <- Timestamp.current[F]
      cont <- contacts(v.id)
      org = ROrganization(
        v.id,
        cid,
        v.name.trim,
        v.address.street.trim,
        v.address.zip.trim,
        v.address.city.trim,
        v.address.country.trim,
        v.notes,
        v.created,
        now,
        v.shortName.map(_.trim),
        v.use
      )
    } yield OOrganization.OrgAndContacts(org, cont)
  }

  def mkPerson(v: OOrganization.PersonAndContacts): Person = {
    val rp = v.person
    Person(
      rp.pid,
      rp.name,
      v.org.map(o => IdName(o.oid, o.name)),
      Address(rp.street, rp.zip, rp.city, rp.country),
      v.contacts.map(mkContact).toList,
      rp.notes,
      rp.use,
      rp.created
    )
  }

  def newPerson[F[_]: Sync](v: Person, cid: Ident): F[OOrganization.PersonAndContacts] = {
    def contacts(pid: Ident) =
      v.contacts.traverse(c => newContact(c, None, pid.some))
    for {
      now  <- Timestamp.current[F]
      pid  <- Ident.randomId[F]
      cont <- contacts(pid)
      pers = RPerson(
        pid,
        cid,
        v.name.trim,
        v.address.street.trim,
        v.address.zip.trim,
        v.address.city.trim,
        v.address.country.trim,
        v.notes,
        now,
        now,
        v.organization.map(_.id),
        v.use
      )
    } yield OOrganization.PersonAndContacts(pers, None, cont)
  }

  def changePerson[F[_]: Sync](
      v: Person,
      cid: Ident
  ): F[OOrganization.PersonAndContacts] = {
    def contacts(pid: Ident) =
      v.contacts.traverse(c => newContact(c, None, pid.some))
    for {
      now  <- Timestamp.current[F]
      cont <- contacts(v.id)
      pers = RPerson(
        v.id,
        cid,
        v.name.trim,
        v.address.street.trim,
        v.address.zip.trim,
        v.address.city.trim,
        v.address.country.trim,
        v.notes,
        v.created,
        now,
        v.organization.map(_.id),
        v.use
      )
    } yield OOrganization.PersonAndContacts(pers, None, cont)
  }

  // contact
  def mkContact(rc: RContact): Contact =
    Contact(rc.contactId, rc.value, rc.kind)

  def newContact[F[_]: Sync](
      c: Contact,
      oid: Option[Ident],
      pid: Option[Ident]
  ): F[RContact] =
    timeId.map { case (id, now) =>
      RContact(id, c.value.trim, c.kind, pid, oid, now)
    }

  // users
  def mkUser(ru: RUser): User =
    User(
      ru.uid,
      ru.login,
      ru.state,
      None,
      ru.email,
      ru.lastLogin,
      ru.loginCount,
      ru.created
    )

  def newUser[F[_]: Sync](u: User, cid: Ident): F[RUser] =
    timeId.map { case (id, now) =>
      RUser(
        id,
        u.login,
        cid,
        u.password.getOrElse(Password.empty),
        u.state,
        u.email,
        0,
        None,
        now
      )
    }

  def changeUser(u: User, cid: Ident): RUser =
    RUser(
      u.id,
      u.login,
      cid,
      u.password.getOrElse(Password.empty),
      u.state,
      u.email,
      u.loginCount,
      u.lastLogin,
      u.created
    )

  // tags

  def mkTag(rt: RTag): Tag =
    Tag(rt.tagId, rt.name, rt.category, rt.created)

  def newTag[F[_]: Sync](t: Tag, cid: Ident): F[RTag] =
    timeId.map { case (id, now) =>
      RTag(id, cid, t.name.trim, t.category.map(_.trim), now)
    }

  def changeTag(t: Tag, cid: Ident): RTag =
    RTag(t.id, cid, t.name.trim, t.category.map(_.trim), t.created)

  // sources

  def mkSource(s: SourceData): SourceAndTags =
    SourceAndTags(
      Source(
        s.source.sid,
        s.source.abbrev,
        s.source.description,
        s.source.counter,
        s.source.enabled,
        s.source.priority,
        s.source.folderId,
        s.source.fileFilter,
        s.source.language,
        s.source.created,
        s.source.attachmentsOnly
      ),
      TagList(s.tags.length, s.tags.map(mkTag).toList)
    )

  def newSource[F[_]: Sync](s: Source, cid: Ident): F[RSource] =
    timeId.map { case (id, now) =>
      RSource(
        id,
        cid,
        s.abbrev.trim,
        s.description,
        0,
        s.enabled,
        s.priority,
        now,
        s.folder,
        s.fileFilter,
        s.language,
        s.attachmentsOnly
      )
    }

  def changeSource[F[_]](s: Source, coll: Ident): RSource =
    RSource(
      s.id,
      coll,
      s.abbrev.trim,
      s.description,
      s.counter,
      s.enabled,
      s.priority,
      s.created,
      s.folder,
      s.fileFilter,
      s.language,
      s.attachmentsOnly
    )

  // equipment
  def mkEquipment(re: REquipment): Equipment =
    Equipment(re.eid, re.name, re.created, re.notes, re.use)

  def newEquipment[F[_]: Sync](e: Equipment, cid: Ident): F[REquipment] =
    timeId.map { case (id, now) =>
      REquipment(id, cid, e.name.trim, now, now, e.notes, e.use)
    }

  def changeEquipment[F[_]: Sync](e: Equipment, cid: Ident): F[REquipment] =
    Timestamp
      .current[F]
      .map(now => REquipment(e.id, cid, e.name.trim, e.created, now, e.notes, e.use))

  // idref

  def mkIdName(ref: IdRef): IdName =
    IdName(ref.id, ref.name)

  // basic result

  def basicResult(r: SetValueResult): BasicResult =
    r match {
      case SetValueResult.FieldNotFound =>
        BasicResult(false, "The given field is unknown")
      case SetValueResult.ItemNotFound =>
        BasicResult(false, "The given item is unknown")
      case SetValueResult.ValueInvalid(msg) =>
        BasicResult(false, s"The value is invalid: $msg")
      case SetValueResult.Success =>
        BasicResult(true, "Custom field value set successfully.")
    }

  def basicResult(cr: JobCancelResult): BasicResult =
    cr match {
      case JobCancelResult.JobNotFound => BasicResult(false, "Job not found")
      case JobCancelResult.CancelRequested =>
        BasicResult(true, "Cancel was requested at the job executor")
      case JobCancelResult.Removed =>
        BasicResult(true, "The job has been removed from the queue.")
    }

  def idResult(ar: AddResult, id: Ident, successMsg: String): IdResult =
    ar match {
      case AddResult.Success           => IdResult(true, successMsg, id)
      case AddResult.EntityExists(msg) => IdResult(false, msg, Ident.unsafe(""))
      case AddResult.Failure(ex) =>
        IdResult(false, s"Internal error: ${ex.getMessage}", Ident.unsafe(""))
    }

  def basicResult(ar: AddResult, successMsg: String): BasicResult =
    ar match {
      case AddResult.Success           => BasicResult(true, successMsg)
      case AddResult.EntityExists(msg) => BasicResult(false, msg)
      case AddResult.Failure(ex) =>
        BasicResult(false, s"Internal error: ${ex.getMessage}")
    }

  def basicResult(ar: UpdateResult, successMsg: String): BasicResult =
    ar match {
      case UpdateResult.Success  => BasicResult(true, successMsg)
      case UpdateResult.NotFound => BasicResult(false, "Not found")
      case UpdateResult.Failure(ex) =>
        BasicResult(false, s"Internal error: ${ex.getMessage}")
    }

  def basicResult(ur: OUpload.UploadResult): BasicResult =
    ur match {
      case UploadResult.Success  => BasicResult(true, "Files submitted.")
      case UploadResult.NoFiles  => BasicResult(false, "There were no files to submit.")
      case UploadResult.NoSource => BasicResult(false, "The source id is not valid.")
      case UploadResult.NoItem   => BasicResult(false, "The item could not be found.")
    }

  def basicResult(cr: PassChangeResult): BasicResult =
    cr match {
      case PassChangeResult.Success => BasicResult(true, "Password changed.")
      case PassChangeResult.UpdateFailed =>
        BasicResult(false, "The database update failed.")
      case PassChangeResult.PasswordMismatch =>
        BasicResult(false, "The current password is incorrect.")
      case PassChangeResult.UserNotFound => BasicResult(false, "User not found.")
    }

  def basicResult(e: Either[Throwable, _], successMsg: String): BasicResult =
    e match {
      case Right(_) => BasicResult(true, successMsg)
      case Left(ex) => BasicResult(false, ex.getMessage)
    }

  // MIME Type

  def fromContentType(header: `Content-Type`): MimeType =
    MimeType(
      header.mediaType.mainType,
      header.mediaType.subType,
      header.mediaType.extensions
    )
}

object Conversions extends Conversions {

  private def timeId[F[_]: Sync]: F[(Ident, Timestamp)] =
    for {
      id  <- Ident.randomId[F]
      now <- Timestamp.current
    } yield (id, now)
}
