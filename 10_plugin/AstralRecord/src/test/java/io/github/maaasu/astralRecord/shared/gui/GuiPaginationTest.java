package io.github.maaasu.astralRecord.shared.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiPaginationTest {

    @Test
    void totalPagesReturnsAtLeastOne() {
        assertEquals(1, GuiPagination.totalPages(0, 45));
        assertEquals(1, GuiPagination.totalPages(-10, 45));
    }

    @Test
    void totalPagesRoundsUpByPageSize() {
        assertEquals(1, GuiPagination.totalPages(45, 45));
        assertEquals(2, GuiPagination.totalPages(46, 45));
        assertEquals(3, GuiPagination.totalPages(91, 45));
    }

    @Test
    void normalizePageClampsIntoRange() {
        assertEquals(0, GuiPagination.normalizePage(-1, 90, 45));
        assertEquals(1, GuiPagination.normalizePage(99, 90, 45));
        assertEquals(0, GuiPagination.normalizePage(99, 0, 45));
    }

    @Test
    void pageBoundariesUseExclusiveEnd() {
        assertEquals(45, GuiPagination.pageStart(1, 45));
        assertEquals(90, GuiPagination.pageEnd(1, 100, 45));
        assertEquals(100, GuiPagination.pageEnd(2, 100, 45));
    }

    @Test
    void navigationFlagsReflectCurrentPage() {
        assertFalse(GuiPagination.hasPreviousPage(0));
        assertTrue(GuiPagination.hasPreviousPage(1));
        assertTrue(GuiPagination.hasNextPage(0, 46, 45));
        assertFalse(GuiPagination.hasNextPage(1, 46, 45));
    }

    @Test
    void invalidPageSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuiPagination.totalPages(1, 0));
    }
}
