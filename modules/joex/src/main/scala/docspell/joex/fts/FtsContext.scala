/*
 * Copyright 2020 Docspell Contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package docspell.joex.fts

import docspell.backend.fulltext.CreateIndex
import docspell.common.Logger
import docspell.ftsclient.FtsClient
import docspell.joex.Config
import docspell.joex.scheduler.Context
import docspell.store.Store

case class FtsContext[F[_]](
    cfg: Config.FullTextSearch,
    store: Store[F],
    fulltext: CreateIndex[F],
    fts: FtsClient[F],
    logger: Logger[F]
)

object FtsContext {

  def apply[F[_]](
      cfg: Config.FullTextSearch,
      fts: FtsClient[F],
      fulltext: CreateIndex[F],
      ctx: Context[F, _]
  ): FtsContext[F] =
    FtsContext(cfg, ctx.store, fulltext, fts, ctx.logger)
}
