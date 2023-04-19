CREATE TABLE "tm" (
id uuid NOT NULL PRIMARY KEY,
secid uuid,
acc_id uuid,
ccatz character varying,
tcred boolean DEFAULT false ,
am numeric(21,5) ,
set_date timestamp with time zone ,
created timestamp with time zone,
updated timestamp with time zone,
events_id uuid,
events_transaction_id uuid ,
events_status character varying,
events_payment_snapshot jsonb,
events_created timestamp with time zone,
vid uuid,
vtid uuid ,
vstatus character varying ,
vamount numeric(21,5) ,
vcreated timestamp with time zone,
vbilling_currency character varying
);

ALTER TABLE public.tm REPLICA IDENTITY FULL;


INSERT INTO public.tm VALUES (
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f71',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
'IDR',
't',
200000.00000,
'2022-10-16 16:53:15.01957',
'2022-10-16 16:53:15.01957',
'2022-10-16 16:53:15.01957',
'b4763f4a-2e3f-41ae-9715-4ab113e2f53c',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
NULL,
'{"Hello":"World"}',
'2022-10-16 16:53:15.01957',
NULL,
NULL,
NULL,
NULL,
NULL,
NULL
);

insert into public.tm values(
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f73',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
'IDR',
't',
200000.00000,
'2022-10-16 16:53:15.01957',
'2022-10-16 16:53:15.01957',
'2022-10-16 16:53:15.01957',
'b4763f4a-2e3f-41ae-9715-4ab113e2f53c',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
NULL,
NULL,
'2022-10-16 16:53:15.01957',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f71',
'9cb52b2a-8ef2-4987-8856-c79a1b2c2f72',
'COMPLETED',
200000.00000,
'2022-10-16 16:53:15.01957',
'IDR'
);
