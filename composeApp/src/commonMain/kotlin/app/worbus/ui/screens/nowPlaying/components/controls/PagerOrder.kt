package app.worbus.ui.screens.nowPlaying.components.controls

import app.worbus.shared.PlayOrder

/*
 * Which queue row each page of the cover pager shows (B-343). Under shuffle the pages follow the
 * play order, as the peek strip and the queue list do (B-341): page p shows row order[p], the
 * playing row's page is its play position, and a settled swipe plays the row its page shows. In
 * queue order, or with an order that is not of exactly these rows (a frame ahead or behind), page
 * p is row p.
 */

/** The queue row page [page] shows, in a queue of [count] rows played in [order]. */
fun pagerRow(page: Int, count: Int, order: List<Int>?): Int =
	if (PlayOrder.isPermutation(order, count) && page in 0 until count) order!![page] else page

/** The page that shows queue row [row] (-1 for none). */
fun pagerPage(row: Int, count: Int, order: List<Int>?): Int =
	if (PlayOrder.isPermutation(order, count) && row in 0 until count) order!!.indexOf(row) else row
