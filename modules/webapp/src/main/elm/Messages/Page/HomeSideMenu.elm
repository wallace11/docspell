{-
   Copyright 2020 Docspell Contributors

   SPDX-License-Identifier: GPL-3.0-or-later
-}


module Messages.Page.HomeSideMenu exposing
    ( Texts
    , de
    , gb
    )

import Messages.Comp.ItemDetail.MultiEditMenu
import Messages.Comp.SearchMenu


type alias Texts =
    { searchMenu : Messages.Comp.SearchMenu.Texts
    , multiEdit : Messages.Comp.ItemDetail.MultiEditMenu.Texts
    , editMode : String
    , resetSearchForm : String
    , multiEditHeader : String
    , multiEditInfo : String
    , close : String
    }


gb : Texts
gb =
    { searchMenu = Messages.Comp.SearchMenu.gb
    , multiEdit = Messages.Comp.ItemDetail.MultiEditMenu.gb
    , editMode = "Edit Mode"
    , resetSearchForm = "Reset search form"
    , multiEditHeader = "Multi-Edit"
    , multiEditInfo = "Note that a change here immediatly affects all selected items on the right!"
    , close = "Close"
    }


de : Texts
de =
    { searchMenu = Messages.Comp.SearchMenu.de
    , multiEdit = Messages.Comp.ItemDetail.MultiEditMenu.de
    , editMode = "Änderungsmodus"
    , resetSearchForm = "Suchformular zurücksetzen"
    , multiEditHeader = "Mehrere Dokumente ändern"
    , multiEditInfo = "Beachte, dass eine Änderung hier direkt auf alle gewählten Dokumente angwendet wird!"
    , close = "Schließen"
    }
