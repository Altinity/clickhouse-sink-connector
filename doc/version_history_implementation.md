MySQL 
```
update products set quantityInStock=413 where productCode='S72_3212';
```

ClickHouse
```
INSERT INTO products
(
  productCode, productName, productLine, productScale, productVendor,
  productDescription, quantityInStock, buyPrice, MSRP,
  _valid_from, _valid_to, _operation, _version, is_deleted
)

-- 1) close current active record
SELECT
  productCode,
  productName,
  productLine,
  productScale,
  productVendor,
  productDescription,
  quantityInStock,
  buyPrice,
  MSRP,
  _valid_from,
  now() AS _valid_to,
  'U' AS _operation,
  2019457375777325131 AS _version,
  0 AS is_deleted
FROM products FINAL
WHERE productCode = 'S24_4258'
  AND _valid_to = toDateTime('2099-12-31 18:00:00','America/Chicago')
  AND is_deleted = 0

UNION ALL

-- 2) new active version
SELECT
  'S24_4258',
  '1936 Chrysler Airflow',
  'Vintage Cars',
  '1:24',
  'Second Gear Diecast',
  'Features opening trunk, working steering system. Color dark green.',
  412,
  CAST(57.46, 'Decimal(10,2)'),
  CAST(97.39, 'Decimal(10,2)'),
  toDateTime('2026-02-05 11:05:32','America/Chicago'),
  toDateTime('2099-12-31 18:00:00','America/Chicago'),
  'U',
  0,
  0

UNION ALL

-- 3) tombstone / deleted version
SELECT
  'S24_4258',
  '1936 Chrysler Airflow',
  'Vintage Cars',
  '1:24',
  'Second Gear Diecast',
  'Features opening trunk, working steering system. Color dark green.',
  4710,
  CAST(57.46, 'Decimal(10,2)'),
  CAST(97.39, 'Decimal(10,2)'),
  toDateTime('2026-02-05 11:05:32','America/Chicago'),
  toDateTime('2099-12-31 18:00:00','America/Chicago'),
  'U',
  2019457375777325131,
  1;

```

```
INSERT INTO `products` (
    `productCode`, `productName`, `productLine`, `productScale`, `productVendor`, 
    `productDescription`, `quantityInStock`, `buyPrice`, `MSRP`, 
    `_valid_from`, `_valid_to`, `_operation`, `_version`, `is_deleted`
) 
SELECT 
    `productCode`, `productName`, `productLine`, `productScale`, `productVendor`, 
    `productDescription`, `quantityInStock`, `buyPrice`, `MSRP`, `_valid_from`,
    now() as `_valid_to`,
    'U' as `_operation`,
    2019187286389817420 as `_version`,
    0 as `is_deleted` 
FROM `products` FINAL 
WHERE `productCode` = 'S72_3212' 
  AND `_valid_to` = toDateTime('2099-12-31 18:00:00', 'America/Chicago') 
  AND `is_deleted` = 0

UNION ALL

SELECT 
    'S72_3212' as `productCode`,
    'Pont Yacht' as `productName`,
    'Ships' as `productLine`,
    '1:72' as `productScale`,
    'Unimax Art Galleries' as `productVendor`,
    'Measures 38 inches Long x 33 3/4 inches High. Includes a stand. Many extras including rigging, long boats, pilot house, anchors, etc. Comes with 2 masts, all square-rigged' as `productDescription`,
    414 as `quantityInStock`,
    CAST(33.30, 'Decimal(10, 2)') as `buyPrice`,
    CAST(54.60, 'Decimal(10, 2)') as `MSRP`,
    toDateTime('2026-02-04 17:12:18', 'America/Chicago') as `_valid_from`,
    toDateTime('2099-12-31 18:00:00', 'America/Chicago') as `_valid_to`,
    'U' as `_operation`,
    2019187286389817421 as `_version`,
    0 as `is_deleted`

UNION ALL

SELECT 
    'S72_3212' as `productCode`,
    'Pont Yacht' as `productName`,
    'Ships' as `productLine`,
    '1:72' as `productScale`,
    'Unimax Art Galleries' as `productVendor`,
    'Measures 38 inches Long x 33 3/4 inches High. Includes a stand. Many extras including rigging, long boats, pilot house, anchors, etc. Comes with 2 masts, all square-rigged' as `productDescription`,
    413 as `quantityInStock`,
    CAST(33.30, 'Decimal(10, 2)') as `buyPrice`,
    CAST(54.60, 'Decimal(10, 2)') as `MSRP`,
    toDateTime('2026-02-04 17:12:18', 'America/Chicago') as `_valid_from`,
    toDateTime('2099-12-31 18:00:00', 'America/Chicago') as `_valid_to`,
    'U' as `_operation`,
    2019187286389817420 as `_version`,
    1 as `is_deleted`
```


MySQL:
```
mysql> update products set quantityInStock=413 where productCode='S24_4258';
```

CH:
```
SELECT
    _operation,
    _valid_from,
    _valid_to,
    productCode,
    quantityInStock
FROM products
FINAL
WHERE productCode = 'S24_4258'

Query id: 28ba0be2-a6e3-4e92-9889-d723994fd5ec

   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
1. │ U          │ 2026-02-05 14:08:47 │ 2099-12-31 18:00:00 │ S24_4258    │             413 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
2. │ C          │ 2026-02-05 14:08:14 │ 2026-02-05 14:08:47 │ S24_4258    │            4710 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘

2 rows in set. Elapsed: 0.006 sec. 
```

MySQL:
```
mysql> update products set quantityInStock=414 where productCode='S24_4258';
```
CH:
```

Query id: b0f596d7-0ae2-44f0-ab12-61a265ea76f5

   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
1. │ U          │ 2026-02-05 14:08:47 │ 2026-02-05 14:11:28 │ S24_4258    │             413 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
2. │ U          │ 2026-02-05 14:11:28 │ 2099-12-31 18:00:00 │ S24_4258    │             414 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
3. │ C          │ 2026-02-05 14:08:14 │ 2026-02-05 14:08:47 │ S24_4258    │            4710 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ```


MySQL:
```
Rows matched: 1  Changed: 1  Warnings: 0

mysql> delete from products where  productCode='S24_4258';
Query OK, 1 row affected (0.00 sec
````

   CH:

   ```
   select _operation, _valid_from, _valid_to, productCode, quantityInStock from products final where productCode='S24_4258' order by _valid_to;

SELECT
    _operation,
    _valid_from,
    _valid_to,
    productCode,
    quantityInStock
FROM products
FINAL
WHERE productCode = 'S24_4258'

Query id: a7e9675f-9d45-4d51-9502-59437f025c95

   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
1. │ C          │ 2026-02-05 14:08:14 │ 2026-02-05 14:08:47 │ S24_4258    │            4710 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
2. │ U          │ 2026-02-05 14:11:28 │ 2026-02-05 14:12:22 │ S24_4258    │             414 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘
   ┌─_operation─┬─────────_valid_from─┬───────────_valid_to─┬─productCode─┬─quantityInStock─┐
3. │ U          │ 2026-02-05 14:08:47 │ 2026-02-05 14:11:28 │ S24_4258    │             413 │
   └────────────┴─────────────────────┴─────────────────────┴─────────────┴─────────────────┘

3 rows in set. Elapsed: 0.009 sec. 


