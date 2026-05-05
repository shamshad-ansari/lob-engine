package io.github.shamshadansari.lobengine.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class DoublyLinkedList<T> implements Iterable<T> {

    public static final class Node<T> {
        public T    value;
        Node<T>     prev;
        Node<T>     next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Node<T> head;
    private final Node<T> tail;
    private int           size;

    public DoublyLinkedList() {
        head      = new Node<>(null);
        tail      = new Node<>(null);
        head.next = tail;
        tail.prev = head;
    }

    // Appends to the back and returns the Node so the caller can hold it
    // for O(1) removal later. This returned Node is what Order.owningNode stores.
    public Node<T> addLast(T value) {
        Node<T> node   = new Node<>(value);
        node.prev      = tail.prev;
        node.next      = tail;
        tail.prev.next = node;
        tail.prev      = node;
        size++;
        return node;
    }

    // O(1) splice-out. Nulls prev/next after removal to prevent
    // accidental double-removal and to release the chain references.
    public T removeNode(Node<T> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev      = null;
        node.next      = null;
        size--;
        return node.value;
    }

    public T peekFirst() {
        return head.next == tail ? null : head.next.value;
    }

    public T pollFirst() {
        if (head.next == tail) return null;
        return removeNode(head.next);
    }

    public boolean isEmpty() { return size == 0; }
    public int     size()    { return size; }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private Node<T> cursor = head.next;

            @Override public boolean hasNext() { return cursor != tail; }

            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                T val  = cursor.value;
                cursor = cursor.next;
                return val;
            }
        };
    }
}
