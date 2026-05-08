package io.github.shamshadansari.lobengine.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DoublyLinkedListTest {

    private DoublyLinkedList<String> list;

    @BeforeEach
    void setUp() {
        list = new DoublyLinkedList<>();
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void newList_isEmpty() {
        assertThat(list.isEmpty()).isTrue();
        assertThat(list.size()).isZero();
    }

    @Test
    void peekFirst_returnsNullOnEmptyList() {
        assertThat(list.peekFirst()).isNull();
    }

    @Test
    void pollFirst_returnsNullOnEmptyList_noException() {
        assertThat(list.pollFirst()).isNull();
    }

    // -------------------------------------------------------------------------
    // addLast / FIFO order via pollFirst
    // -------------------------------------------------------------------------

    @Test
    void addLast_pollFirst_maintainsFifoOrder() {
        list.addLast("A");
        list.addLast("B");
        list.addLast("C");

        assertThat(list.pollFirst()).isEqualTo("A");
        assertThat(list.pollFirst()).isEqualTo("B");
        assertThat(list.pollFirst()).isEqualTo("C");
        assertThat(list.pollFirst()).isNull();
    }

    @Test
    void addLast_returnsNodeWhoseValueMatchesAdded() {
        DoublyLinkedList.Node<String> node = list.addLast("X");

        assertThat(node).isNotNull();
        assertThat(node.value).isEqualTo("X");
    }

    @Test
    void addLast_sizeIncrements() {
        list.addLast("A");
        assertThat(list.size()).isEqualTo(1);

        list.addLast("B");
        assertThat(list.size()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // removeNode — mid-queue splice
    // -------------------------------------------------------------------------

    @Test
    void removeNode_splicesOutMiddleElement() {
        list.addLast("A");
        DoublyLinkedList.Node<String> nodeB = list.addLast("B");
        list.addLast("C");

        String removed = list.removeNode(nodeB);

        assertThat(removed).isEqualTo("B");

        List<String> remaining = iterateAll(list);
        assertThat(remaining).containsExactly("A", "C");
    }

    @Test
    void removeNode_splicesOutFirstElement() {
        DoublyLinkedList.Node<String> nodeA = list.addLast("A");
        list.addLast("B");

        list.removeNode(nodeA);

        assertThat(iterateAll(list)).containsExactly("B");
    }

    @Test
    void removeNode_splicesOutLastElement() {
        list.addLast("A");
        DoublyLinkedList.Node<String> nodeB = list.addLast("B");

        list.removeNode(nodeB);

        assertThat(iterateAll(list)).containsExactly("A");
    }

    @Test
    void removeNode_returnedNodeIsTheSameOnePassedIn() {
        DoublyLinkedList.Node<String> node = list.addLast("A");

        String val = list.removeNode(node);

        assertThat(val).isEqualTo("A");
    }

    @Test
    void removeNode_nullsPrevAndNextPointers() {
        list.addLast("A");
        DoublyLinkedList.Node<String> nodeB = list.addLast("B");
        list.addLast("C");

        list.removeNode(nodeB);

        // prev and next are package-private; access via the same package
        assertThat(nodeB.prev).isNull();
        assertThat(nodeB.next).isNull();
    }

    @Test
    void removeNode_decrementsSize() {
        list.addLast("A");
        DoublyLinkedList.Node<String> nodeB = list.addLast("B");
        list.addLast("C");

        list.removeNode(nodeB);

        assertThat(list.size()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // pollFirst interaction with size
    // -------------------------------------------------------------------------

    @Test
    void pollFirst_decrementsSize() {
        list.addLast("A");
        list.addLast("B");

        list.pollFirst();

        assertThat(list.size()).isEqualTo(1);
        assertThat(list.isEmpty()).isFalse();
    }

    @Test
    void pollFirst_lastElement_listBecomesEmpty() {
        list.addLast("A");
        list.pollFirst();

        assertThat(list.isEmpty()).isTrue();
        assertThat(list.size()).isZero();
    }

    // -------------------------------------------------------------------------
    // peekFirst — non-destructive
    // -------------------------------------------------------------------------

    @Test
    void peekFirst_doesNotRemoveElement() {
        list.addLast("A");

        assertThat(list.peekFirst()).isEqualTo("A");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.peekFirst()).isEqualTo("A");   // still there
    }

    // -------------------------------------------------------------------------
    // size() accuracy through mixed operations
    // -------------------------------------------------------------------------

    @Test
    void size_isAccurateThroughMixedOperations() {
        DoublyLinkedList.Node<String> a = list.addLast("A");   // size 1
        list.addLast("B");                                      // size 2
        DoublyLinkedList.Node<String> c = list.addLast("C");   // size 3

        list.removeNode(a);   // size 2
        assertThat(list.size()).isEqualTo(2);

        list.pollFirst();     // removes B, size 1
        assertThat(list.size()).isEqualTo(1);

        list.removeNode(c);   // size 0
        assertThat(list.size()).isZero();
        assertThat(list.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Iterator
    // -------------------------------------------------------------------------

    @Test
    void iterator_traversesInInsertionOrder() {
        list.addLast("X");
        list.addLast("Y");
        list.addLast("Z");

        assertThat(iterateAll(list)).containsExactly("X", "Y", "Z");
    }

    @Test
    void iterator_onEmptyList_hasNoElements() {
        assertThat(iterateAll(list)).isEmpty();
    }

    @Test
    void iterator_afterMidRemove_skipsRemovedElement() {
        list.addLast("A");
        DoublyLinkedList.Node<String> nodeB = list.addLast("B");
        list.addLast("C");

        list.removeNode(nodeB);

        assertThat(iterateAll(list)).containsExactly("A", "C");
    }

    @Test
    void iterator_next_throwsNoSuchElementWhenExhausted() {
        list.addLast("A");
        var it = list.iterator();
        it.next();   // consume "A"

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(it::next);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private <T> List<T> iterateAll(DoublyLinkedList<T> dll) {
        List<T> result = new ArrayList<>();
        for (T item : dll) result.add(item);
        return result;
    }
}
