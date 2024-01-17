import string
import random


def generate_random_name(length=5):
    # Generates a random string of uppercase letters and digits
    characters = string.ascii_uppercase + string.digits
    return "".join(random.choice(characters) for i in range(length))
