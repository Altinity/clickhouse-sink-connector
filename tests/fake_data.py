from faker import Faker

class FakeData:

    def get_fake_row(primary_num):
        fake = Faker()

    #emp_no,birth_date,first_name,last_name,gender,hire_date,salary,num_years,
        # bonus,small_value,int_value,discount,num_years_signed,bonus_signed,
        # small_value_signed,int_value_signed,last_modified_date_time,
        # last_access_time,married_status,perDiemRate,hourlyRate,jobDescription,updated_time

        row = (primary_num, fake.date(), fake.name()[:10], fake.name()[:10], 'M', fake.date(),
               fake.unique.random_int(), fake.pyint(0, 10),
               fake.unique.random_int(), fake.unique.random_int(),
               fake.unique.random_int(), fake.unique.random_int(),
               fake.unique.pyint(0, -10, -1), fake.unique.random_int(),
               fake.unique.random_int(), fake.unique.random_int(),
               fake.date_time(), fake.time(), 'M', fake.pyfloat(), fake.pyfloat(), fake.job(),
               fake.date_time())
        return row

    @classmethod
    def get_fake_products_row(cls):

        #CREATE TABLE products(
        #    `productCode` String, `productName` String, `productLine` String, `productScale` String,
        #    `productVendor` String, `productDescription` String, quantityInStock` Int32, `buyPrice` Decimal(10,2),
        #                                                                                            `MSRP` Decimal(10,2)

        fake = Faker()
        row = (fake.bs()[:10], fake.name()[:10], fake.bs()[:10], fake.bs()[:10], fake.company()[:10], \
              fake.company()[:10], fake.unique.pyint(0, 100), fake.pyfloat(min_value=0, max_value=1), fake.pyfloat(min_value=0, max_value=2))

        return row