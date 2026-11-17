package com.ruangpay.service;

import com.ruangpay.model.Transaction;
import java.util.ArrayList;
import java.util.List;

public final class RecentTransactionList {
    private static final class Node {
        Transaction value; Node prev; Node next;
        Node(Transaction value) { this.value = value; }
    }
    private Node head;
    private Node tail;
    private int size;
    private final int limit;

    public RecentTransactionList(int limit) { this.limit = limit; }

    public synchronized void addFirst(Transaction transaction) {
        Node node = new Node(transaction);
        node.next = head;
        if (head != null) head.prev = node;
        head = node;
        if (tail == null) tail = node;
        size++;
        if (size > limit) {
            tail = tail.prev;
            if (tail != null) tail.next = null;
            size--;
        }
    }

    public synchronized List<Transaction> snapshot() {
        List<Transaction> result = new ArrayList<>();
        Node cursor = head;
        while (cursor != null) { result.add(cursor.value); cursor = cursor.next; }
        return result;
    }
}
