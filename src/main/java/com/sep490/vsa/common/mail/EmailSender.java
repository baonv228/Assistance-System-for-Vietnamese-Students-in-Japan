package com.sep490.vsa.common.mail;

public interface EmailSender {

    void send(String to, String subject, String text);
}
