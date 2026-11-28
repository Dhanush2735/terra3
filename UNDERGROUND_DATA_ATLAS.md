# Terra3 underground data atlas (research only)

For any Earth coordinate, report a value with provenance as mapped, modeled, generated, or unknown. Global reach is not continuous verified coverage. A surface map or point is never permission to invent exact underground geometry.

| Layer | Explore / source | Terra3 rule |
|---|---|---|
| Rock | https://macrostrat.org/map/ ; https://doi.pangaea.de/10.1594/PANGAEA.788537 | Regional surface-rock family; local measured strata override; unknown fallback. |
| Groundwater | https://ggis.un-igrac.org/ ; https://doi.org/10.1126/science.1229881 ; https://doi.org/10.1029/2017GL075860 | Wells are observations at a place/time; Fan grid estimates water-table depth; GLHYMPS estimates flow properties, not water level. Pilot bounded wet pockets only and verify no flooding at entrances or structures. |
| Ores | https://mrdata.usgs.gov/ ; https://mrdata.usgs.gov/pp1802/ | Known occurrence and commodity, not deposit geometry. Use region/host rock as hints, and balance simulated Minecraft veins separately. Missing records do not mean no ore. |
| Caves | https://grottocenter.org/ui/map/45.72423528362707,6.093984246253968,20 ; https://www.deutscher-rohstoffeffizienz-preis.de/whymap/EN/Maps_Data/Wokam/whymap_ed2017_map_40m.html | WOKAM indicates karst context, not entrances; existing Phase 2 records locate known sites. Do not rerun completed crawl or expose sensitive locations. Surveyed geometry is a separate Phase 3 import with per-survey permissions. |
| Mines/tunnels | https://mrdata.usgs.gov/ ; https://wiki.openstreetmap.org/wiki/Key:tunnel | Mine sites and mapped tunnel routes are different data; no depth, cross-section or shaft placement inferred as exact. Preserve Phase 2 testing order; tunnel scope belongs to Phase 3. |
| Loose deposits | https://doi.pangaea.de/10.1594/PANGAEA.884822 | Regional sediment type; don't interpret as measured thickness. |
| Seabed sediment | https://www.ncei.noaa.gov/access/metadata/landing-page/bin/iso?id=gov.noaa.ngdc.mgg.geophysics%3AG01065_v3 | GlobSed provides broad total thickness, not block composition; no direct copy to Minecraft depth. |
| Volcanism | https://volcano.si.edu/ | Volcano inventory gives location/context, not underground lava geometry. No automatic lava layer or reservoir. |

Build order: rock-only preset → saved-block cave/structure regression → bounded groundwater pilot → balanced ore pilot → loose deposits and other details. Cache only versioned inputs with verified redistribution terms, source checksums and explicit unknowns. No network requests during chunk generation. Map views are for exploring evidence, not proof of rendered blocks. No generation code or save changed by this research.
