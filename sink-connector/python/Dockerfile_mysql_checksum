# Create a dockerfile of db db_compare folders.
# docker run -e MYSQL_HOST=mysql_host_value -e MYSQL_DATABASE=mysql_database_value -e MYSQL_USER=mysql_user_value -e MYSQL_PASSWORD=mysql_password_value -it --rm db_compare:latest
FROM python:3.10
WORKDIR /app

ENV MYSQL_HOST mysql_host_value
ENV MYSQL_DATABASE mysql_database_value
ENV MYSQL_USER mysql_user_value
ENV MYSQL_PASSWORD mysql_password_value

COPY db db
COPY db_compare db_compare

COPY requirements.txt requirements.txt
RUN pip install -r requirements.txt

RUN cd /app/db && export PYTHONPATH=.
RUN cd ..
ENV PYTHONPATH "${PYTHONPATH}:/app/db"
ENTRYPOINT ["python3.10", "/app/db_compare/mysql_table_checksum.py","--mysql_host", "$MYSQL_HOST", "--mysql_database", "$MYSQL_DATABASE", "--mysql_user", "$MYSQL_USER", "--mysql_password", "$MYSQL_PASSWORD", "--tables_regex", "."]


