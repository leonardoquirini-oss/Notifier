package com.containermgmt.notifier.model;


import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

/**
 * Model per la tabella email_list_recipients
 */
@Table("email_list_recipients")
@IdName("id_recipient")
@IdGenerator("nextval('email_list_recipients_id_recipient_seq')")
public class EmailListRecipient extends Model {

    static {
        validatePresenceOf("id_list", "email_address", "recipient_type");

        // Validazione email formato base
        validateRegexpOf("email_address", "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            .message("deve essere un indirizzo email valido");

        // Validazione recipient_type
        // Nota: Validazione custom per recipient_type gestita manualmente
    }
}
