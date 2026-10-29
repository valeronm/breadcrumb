package io.github.valeronm.breadcrumb.boundaryfixture.domain.atlas

import io.github.valeronm.breadcrumb.boundaryfixture.domain.AtlasInput
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow

class AtlasReachesDomain(val other: ReachesSharedRow)

class AtlasSibling

class AtlasValue(val input: AtlasInput, val sibling: AtlasSibling)
