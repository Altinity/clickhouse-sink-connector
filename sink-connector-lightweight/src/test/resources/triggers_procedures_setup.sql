-- Create Database and Tables
DROP DATABASE IF EXISTS test_db;
CREATE DATABASE test_db;
USE test_db;

-- Create Customers table
CREATE TABLE customers (
    customer_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create Orders table
CREATE TABLE orders (
    order_id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_amount DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
);

-- Create Audit Logs table
CREATE TABLE audit_logs (
    log_id INT AUTO_INCREMENT PRIMARY KEY,
    action_type VARCHAR(50),
    action_details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Populate initial test data
INSERT INTO customers (name, email) VALUES 
    ('John Doe', 'john.doe@example.com'), 
    ('Jane Smith', 'jane.smith@example.com');
INSERT INTO orders (customer_id, order_amount) VALUES 
    (1, 100.00), 
    (2, 200.50);

-- Create Triggers
DELIMITER $$

-- AFTER INSERT Trigger
CREATE TRIGGER after_customer_insert
AFTER INSERT ON customers
FOR EACH ROW
BEGIN
    INSERT INTO audit_logs (action_type, action_details)
    VALUES ('INSERT', CONCAT('Customer added: ID=', NEW.customer_id, ', Name=', NEW.name));
END$$

-- BEFORE UPDATE Trigger
CREATE TRIGGER before_customer_email_update
BEFORE UPDATE ON customers
FOR EACH ROW
BEGIN
    IF NEW.email <> OLD.email THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Email updates are not allowed.';
    END IF;
END$$

-- AFTER DELETE Trigger
CREATE TRIGGER after_customer_delete
AFTER DELETE ON customers
FOR EACH ROW
BEGIN
    INSERT INTO audit_logs (action_type, action_details)
    VALUES ('DELETE', CONCAT('Customer deleted: ID=', OLD.customer_id, ', Name=', OLD.name));
END$$

-- Create Stored Procedures
CREATE PROCEDURE insert_order(IN customer_id INT, IN order_amount DECIMAL(10, 2))
BEGIN
    INSERT INTO orders (customer_id, order_amount)
    VALUES (customer_id, order_amount);

    INSERT INTO audit_logs (action_type, action_details)
    VALUES ('INSERT', CONCAT('Order added: CustomerID=', customer_id, ', Amount=', order_amount));
END$$

CREATE PROCEDURE customer_report()
BEGIN
    SELECT customers.customer_id, customers.name, customers.email, 
           COUNT(orders.order_id) AS total_orders, SUM(orders.order_amount) AS total_spent
    FROM customers
    LEFT JOIN orders ON customers.customer_id = orders.customer_id
    GROUP BY customers.customer_id;
END$$

CREATE PROCEDURE delete_customer(IN target_customer_id INT)
BEGIN
    -- Log deletion of orders
    INSERT INTO audit_logs (action_type, action_details)
    VALUES ('DELETE', CONCAT('Orders deleted for CustomerID=', target_customer_id));

    -- Delete orders
    DELETE FROM orders WHERE customer_id = target_customer_id;

    -- Log deletion of customer
    INSERT INTO audit_logs (action_type, action_details)
    VALUES ('DELETE', CONCAT('Customer deleted: ID=', target_customer_id));

    -- Delete customer
    DELETE FROM customers WHERE customer_id = target_customer_id;
END$$

-- Create Functions
CREATE FUNCTION get_total_order_amount(p_customer_id INT) RETURNS DECIMAL(10, 2)
DETERMINISTIC
BEGIN
    DECLARE total_amount DECIMAL(10, 2);
    SELECT SUM(order_amount) INTO total_amount
    FROM orders
    WHERE customer_id = p_customer_id;

    RETURN COALESCE(total_amount, 0.00);
END$$

CREATE FUNCTION get_customer_info(p_customer_id INT) RETURNS JSON
DETERMINISTIC
BEGIN
    RETURN (SELECT JSON_OBJECT('customer_id', p_customer_id, 'name', name, 'email', email)
            FROM customers
            WHERE customer_id = p_customer_id);
END$$

CREATE FUNCTION count_orders(p_customer_id INT) RETURNS INT
DETERMINISTIC
BEGIN
    DECLARE order_count INT;
    SELECT COUNT(*) INTO order_count
    FROM orders
    WHERE customer_id = p_customer_id;

    RETURN order_count;
END$$

DELIMITER ; 