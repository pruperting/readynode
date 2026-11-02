package uk.plumridge.readynode;

import static org.junit.Assert.*;

import org.junit.Test;

/** The sharing timer's choices: 5-60 minutes in fives, or 1-12 hours, kept as minutes. */
public class TimerTest {
    @Test
    public void choicesBecomeMinutes() {
        assertEquals(5, NodeService.timerMinutes(0, false));
        assertEquals(60, NodeService.timerMinutes(11, false));
        assertEquals(60, NodeService.timerMinutes(0, true));
        assertEquals(720, NodeService.timerMinutes(11, true));
        assertEquals(720, NodeService.timerMinutes(99, true));      // out of range is clamped, never a crash
        assertEquals(5, NodeService.timerMinutes(-1, false));
    }

    @Test
    public void savedSettingsShowInTheRightUnit() {
        assertArrayEquals(new int[]{5, 0}, NodeService.timerPick(30));     // 30 minutes
        assertArrayEquals(new int[]{11, 0}, NodeService.timerPick(60));    // 60 minutes stays in minutes
        assertArrayEquals(new int[]{1, 1}, NodeService.timerPick(120));    // 3.4's "2 hours"
        assertArrayEquals(new int[]{7, 1}, NodeService.timerPick(480));    // 3.4's "8 hours"
        assertArrayEquals(new int[]{0, 0}, NodeService.timerPick(3));      // nearest choice, never below 5 minutes
    }

    @Test
    public void everyChoiceRoundTrips() {
        for (boolean hours : new boolean[]{false, true})
            for (int i = 0; i < NodeService.timerChoices(hours).length; i++) {
                int min = NodeService.timerMinutes(i, hours);
                int[] p = NodeService.timerPick(min);
                assertEquals(min, NodeService.timerMinutes(p[0], p[1] == 1));
            }
    }
}
