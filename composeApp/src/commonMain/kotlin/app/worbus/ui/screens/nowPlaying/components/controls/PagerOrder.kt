package app.worbus.ui.screens.nowPlaying.components.controls

/*
 * Which queue row each page of the cover pager shows (B-343). Today: page p is queue row p.
 */

/** The queue row page [page] shows, in a queue of [count] rows played in [order]. */
fun pagerRow(page: Int, count: Int, order: List<Int>?): Int = page

/** The page that shows queue row [row]. */
fun pagerPage(row: Int, count: Int, order: List<Int>?): Int = row
