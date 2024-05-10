
CREATE TABLE "tm" (
id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
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

CREATE TABLE protocol_test
(
  id bigserial NOT NULL,
  consultation_id int8 NOT NULL,
  recomendation text NULL,
  create_date timestamptz(0) NOT NULL DEFAULT now(),
CONSTRAINT protocol_pkey PRIMARY KEY (id)
);

INSERT INTO protocol_test VALUES ('1778392', '15563836', 'Henry I voyuer Ellen Holdings LLC 4510 Elsmore viking friendship', '2001-11-17T08:34:48.338Z');
INSERT INTO protocol_test VALUES ('1778393', '17226989', 'Egbert  modify Clothing Stores Inc 6497 Macclesfield Street touched journey', '2006-03-08T14:57:41.217Z');
INSERT INTO protocol_test VALUES ('1778394', '10588494', 'Aethelbert incorrect Configuration Company 0652 Bellhouse Avenue update descending', '1998-06-17T19:56:22.373Z');
INSERT INTO protocol_test VALUES ('1778395', '929854', 'William III roster Proxy Energy  3851 Tamerton Lane operated assault', '1976-04-27T09:57:28.520Z');
INSERT INTO protocol_test VALUES ('1778396', '3802619', 'Edward VI phentermine Intent  2052 Mardale Road apparent negotiation', '1974-11-21T03:13:00.486Z');
INSERT INTO protocol_test VALUES ('1778397', '14180264', 'Oliver Cromwell leaders Levitra Corporation 4607 Blackmore Street ex factor', '2019-01-19T17:07:00.548Z');
INSERT INTO protocol_test VALUES ('1778398', '4694776', 'Edward IV disposition Casa A.G 1014 Windsor Circle noted nervous', '2001-09-04T16:41:35.782Z');
INSERT INTO protocol_test VALUES ('1778399', '17504297', 'George VI personality Infrastructure Stores Pte. Ltd 5416 Isherwood Avenue beastality surgical', '1973-10-31T09:33:52.476Z');
INSERT INTO protocol_test VALUES ('1778400', '1849158', 'Edward III indication Industrial International Inc 5362 Brow programs fountain', '1989-04-24T16:16:17.682Z');
INSERT INTO protocol_test VALUES ('1778401', '11321075', 'Henry III valves People  7759 Ashdale Road pharmaceuticals eddie', '1984-06-24T04:37:33.883Z');
INSERT INTO protocol_test VALUES ('1778402', '14017777', 'Anne matches Pro SIA 8300 Daisygate Lane calibration keno', '1990-05-22T12:08:53.159Z');
INSERT INTO protocol_test VALUES ('1778403', '20704443', 'Offa msn Knee International S.A 4174 Bodmin Road however portions', '2004-06-13T09:51:21.807Z');
INSERT INTO protocol_test VALUES ('1778404', '13219949', 'Edward III domestic Fragrances Mutual A.G 2683 Brewery Avenue business pen', '2013-03-13T19:38:56.313Z');
INSERT INTO protocol_test VALUES ('1778405', '12850499', 'Harthacanut canvas Scoring Mutual  7526 Imperial Avenue broker absent', '2010-12-17T21:41:22.045Z');
INSERT INTO protocol_test VALUES ('1778406', '9028650', 'Harold II jobs Gamespot A.G 5029 Maypool speaker shorter', '2005-03-22T09:04:38.300Z');
INSERT INTO protocol_test VALUES ('1778407', '124999', 'Oliver Cromwell generating Loud Stores Corp 1309 Tennyson Road village lopez', '1971-01-31T00:13:14.868Z');
INSERT INTO protocol_test VALUES ('1778408', '8208132', 'Charles I  pat Requiring Ltd 5633 Fistral Street velvet keep', '1999-09-18T10:31:12.849Z');
INSERT INTO protocol_test VALUES ('1778409', '11043995', 'Charles II objects Estimated Mutual S.A 3911 Ridge Lane distributors exciting', '1994-07-18T19:38:17.970Z');
INSERT INTO protocol_test VALUES ('1778410', '18749480', 'Henry III trail Codes Software Inc 9875 Thomas Circle psi ion', '2006-12-09T21:56:40.567Z');
INSERT INTO protocol_test VALUES ('1778411', '7187805', 'Edward VII kyle Rca Stores B.V 5530 Lesser Road contemporary nr', '2003-03-25T03:34:25.305Z');
INSERT INTO protocol_test VALUES ('1778412', '15110317', 'William IV dishes Para Software GmbH 2986 Fitton Avenue defining turbo', '1981-03-10T07:11:28.227Z');
INSERT INTO protocol_test VALUES ('1778413', '19393663', 'Stephen wax Suggested  8909 Torwood planning committed', '1993-05-22T20:58:55.054Z');
INSERT INTO protocol_test VALUES ('1778414', '7891828', 'Aethelred I schedule Dicke SIA 5202 Hathaway Avenue sets requested', '2002-11-24T11:17:19.723Z');
INSERT INTO protocol_test VALUES ('1778415', '10389394', 'Harthacanut incidence Locally International Pte. Ltd 2172 Ashwood Road end nato', '2017-03-12T01:23:42.442Z');
INSERT INTO protocol_test VALUES ('1778416', '9613139', 'George I weblog Chi Mutual SIA 0947 Dowling Road satin courses', '2019-06-02T10:57:33.557Z');
INSERT INTO protocol_test VALUES ('1778417', '3294112', 'Ethelred II the Unready diversity Adjust S.A 0876 Farmside Circle thumbzilla edge', '1982-04-12T06:21:46.045Z');
INSERT INTO protocol_test VALUES ('1778418', '22868110', 'Aethelbald batch Twenty  4871 Heaton Lane even punch', '2002-07-24T10:39:09.013Z');
INSERT INTO protocol_test VALUES ('1778419', '9784823', 'Charles I  dinner Tracking Stores A.G 1793 Hurst Circle charming proposals', '1991-03-12T16:10:01.117Z');
INSERT INTO protocol_test VALUES ('1778420', '17503042', 'Richard Cromwell journals Lease  7747 Back Road patrick periodically', '1992-12-17T08:25:26.611Z');
INSERT INTO protocol_test VALUES ('1778421', '4105988', 'Egbert  weak Qualifying Stores Corp 1025 Falmer Street plants engines', '1993-02-16T06:50:46.555Z');
INSERT INTO protocol_test VALUES ('1778422', '20367835', 'Edward III layout Opening International Ltd 9873 Stansfield cocktail mails', '1978-09-25T18:43:38.845Z');
INSERT INTO protocol_test VALUES ('1778423', '4133456', 'Richard Cromwell forced Gtk B.V 6057 Brownsville volvo encourages', '1979-08-10T06:13:07.451Z');
INSERT INTO protocol_test VALUES ('1778424', '15763048', 'George III women Part Ltd 2830 Turf Lane ap diseases', '1978-04-24T19:44:06.966Z');
INSERT INTO protocol_test VALUES ('1778425', '13783051', 'George II wagon Express Energy Pte. Ltd 7317 Adair refer producer', '1976-11-04T05:18:23.814Z');
INSERT INTO protocol_test VALUES ('1778426', '5048883', 'Ethelred II the Unready retain Medline Mutual A.G 6841 Cudworth chester mg', '1971-05-23T22:24:10.661Z');
INSERT INTO protocol_test VALUES ('1778427', '19987357', 'James I ireland Salary Industries  9869 Northholt Road vernon reactions', '2023-02-26T00:29:05.151Z');
INSERT INTO protocol_test VALUES ('1778428', '9518612', 'Stephen poly Holmes LLC 9482 Honiton Road participants equations', '1979-08-04T04:31:07.812Z');
INSERT INTO protocol_test VALUES ('1778429', '9288848', 'William IV printers Parker Industries Corporation 3335 Privet Lane null prostate', '1976-03-05T15:27:18.569Z');
INSERT INTO protocol_test VALUES ('1778430', '12041969', 'Henry VII stocks Skin A.G 1983 Affetside Lane shanghai sleeve', '1970-09-28T17:34:52.197Z');
INSERT INTO protocol_test VALUES ('1778431', '1228695', 'Mary I silver Visiting  6041 Caesar Street cameras judge', '1970-11-07T02:49:21.977Z');
INSERT INTO protocol_test VALUES ('1778432', '21481203', 'Edward V  prisoners Peterson Pte. Ltd 2577 Limebank Circle span religious', '1975-02-02T01:13:06.152Z');

--
--CREATE TABLE test
--(
--  id bigserial NOT NULL,
--  consultation_id int8 NOT NULL,
--  recomendation text NULL,
--date1 timestamp with time zone NOT NULL DEFAULT now(),
--date2 timestamp(0) with time zone NOT NULL DEFAULT now(),
--date3 timestamp with time zone NOT NULL DEFAULT '2023-04-21 21:43:11.965283+03',
--date4 timestamp(0) with time zone NOT NULL DEFAULT '2023-04-21 21:43:32+03',
--date5 timestamp without time zone NOT NULL DEFAULT now(),
--CONSTRAINT protocol_pkey PRIMARY KEY (id)
--);
--

create schema public2;
set schema 'public2';
CREATE TABLE "tm2" (id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY, secid uuid, acc_id uuid);