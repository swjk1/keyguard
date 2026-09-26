"""Entity vocabulary for the synthetic child-safety corpus.

Faker supplies the long tail — street names, surnames, cities — so the model cannot
memorise a closed set of address strings. Everything a child would actually type, and
everything whose *surface form* carries the safety signal, is curated by hand instead:
Faker's `time()` gives "14:37:02", and no twelve-year-old has ever typed that.

Every value is returned as a `Rendered`: the literal string plus the PII entity it should
be labelled with, so a template can lay out a sentence and get token spans for free. That
is the trick that lets the synthetic corpus supervise *both* heads. OpenPII teaches
addresses in business prose; this teaches the same entities in the register the phone
actually sees.
"""

from __future__ import annotations

import random
from dataclasses import dataclass

from faker import Faker

# --------------------------------------------------------------------------------------
# Curated vocabulary
# --------------------------------------------------------------------------------------

STREET_TYPES_FULL = [
    "Street", "Avenue", "Road", "Drive", "Lane", "Court", "Place", "Way",
    "Boulevard", "Circle", "Terrace", "Crescent", "Trail",
]

# The abbreviation is the adversarial case from §23 — "24 oak st" must be as recognisable
# as "24 Oak Street". Keyed by the full form so a template can pick either surface for the
# same underlying street.
STREET_TYPE_ABBREV = {
    "Street": ["St", "St.", "st"],
    "Avenue": ["Ave", "Ave.", "ave"],
    "Road": ["Rd", "Rd.", "rd"],
    "Drive": ["Dr", "Dr.", "dr"],
    "Lane": ["Ln", "Ln."],
    "Court": ["Ct", "Ct."],
    "Place": ["Pl", "Pl."],
    "Boulevard": ["Blvd", "Blvd."],
    "Circle": ["Cir", "Cir."],
    "Terrace": ["Ter", "Ter."],
    "Crescent": ["Cres", "Cr."],
    "Way": ["Way"],
    "Trail": ["Trl"],
}

STREET_NAMES = [
    "Oak", "Maple", "Pine", "Cedar", "Elm", "Birch", "Willow", "Chestnut", "Walnut",
    "Spruce", "Aspen", "Poplar", "Sycamore", "Hickory",
    "Main", "High", "Church", "Mill", "Park", "Bridge", "Union", "Market", "Water",
    "Spring", "Summer", "Winter", "Sunset", "Sunrise", "Hillside", "Lakeview",
    "Riverside", "Meadow", "Orchard", "Garden", "Forest", "Valley", "Ridge",
    "King", "Queen", "Victoria", "Albert", "Wellington", "Lincoln", "Jackson",
    "Franklin", "Madison", "Jefferson", "Washington", "Adams", "Monroe",
    "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh",
]

SCHOOL_PREFIXES = [
    "Lincoln", "Jefferson", "Roosevelt", "Kennedy", "Washington", "Madison", "Franklin",
    "Riverside", "Northside", "Southgate", "Westview", "Eastwood", "Hillcrest",
    "Oakridge", "Maplewood", "Pinecrest", "Lakeshore", "Brookfield", "Fairview",
    "Greenwood", "Stonebridge", "Clearwater", "Highland", "Meadowbrook",
    "St. Mary's", "St. Joseph's", "Holy Cross", "Sacred Heart",
]

SCHOOL_SUFFIXES = [
    "Middle School", "Elementary", "Elementary School", "High School", "Junior High",
    "Academy", "Public School", "Intermediate School", "K-8", "Secondary School",
]

# What a kid actually calls it. Used for the school-context slang variants: "lincoln" on
# its own is still a school reference in "i go to lincoln".
SCHOOL_SHORT_FORMS = ["Lincoln", "Jeff", "Northside", "Westview", "St. Mary's", "Oakridge"]

CLUBS_AND_ACTIVITIES = [
    "practice", "soccer practice", "basketball practice", "swim practice", "band",
    "band practice", "choir", "drama club", "chess club", "robotics", "art club",
    "hockey", "dance", "gymnastics", "karate", "tutoring", "study group",
    "volleyball", "track", "cross country", "debate club", "coding club",
]

PUBLIC_PLACES = [
    "the park", "the mall", "the library", "the rec centre", "the community centre",
    "the skate park", "the corner store", "the bus stop", "the playground",
    "the food court", "the movie theatre", "the pool", "the arcade", "Tim Hortons",
    "the coffee place", "the pizza place", "the burger place", "the gas station",
    "the train station", "the subway station", "the plaza", "the field behind the school",
]

BUSINESS_NAMES = [
    "the pizza place", "the dentist", "the vet", "the barber shop", "the bakery",
    "the pharmacy", "the hardware store", "the bike shop", "the laundromat",
    "Rossi's", "Golden Dragon", "Nonna's", "the corner deli", "the car wash",
]

DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
DAYS_SHORT = ["Mon", "Tues", "Tue", "Wed", "Thurs", "Thu", "Fri", "Sat", "Sun"]

RELATIVES_OTHER = [
    "my mom", "my dad", "my mum", "my aunt", "my uncle", "my grandma", "my grandpa",
    "my cousin", "my brother", "my sister", "my friend", "my best friend",
    "my neighbour", "my teacher", "my coach", "my babysitter", "my stepdad",
]

GUARDIAN_TERMS = [
    "my mom", "my dad", "my mum", "my parents", "my folks", "my rents", "my guardian",
    "my step mom", "my stepdad", "my grandma", "my grandpa",
]

_faker_cache: dict[int, Faker] = {}


def faker_for(seed: int) -> Faker:
    """One Faker per seed, so a given seed reproduces a given corpus exactly."""
    if seed not in _faker_cache:
        fake = Faker("en_US")
        Faker.seed(seed)
        _faker_cache[seed] = fake
    return _faker_cache[seed]


# --------------------------------------------------------------------------------------
# Rendered values
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Rendered:
    """A generated surface string and the PII entity it should be tagged as.

    `parts` lets one slot produce several labelled spans — an address is a BUILDINGNUM
    and a STREET, and labelling "24 Oak St" as one blob would contradict the OpenPII
    supervision the other head is getting.
    """

    text: str
    entity: str | None = None
    parts: tuple[tuple[int, int, str], ...] = ()
    """(start, end, entity) offsets relative to `text`. Overrides `entity` when set."""

    def spans_at(self, offset: int) -> list[dict]:
        if self.parts:
            return [
                {"start": offset + s, "end": offset + e, "label": lab}
                for s, e, lab in self.parts
            ]
        if self.entity:
            return [{"start": offset, "end": offset + len(self.text), "label": self.entity}]
        return []


# --------------------------------------------------------------------------------------
# Generators
# --------------------------------------------------------------------------------------


class Lexicon:
    """Draws entity values. Stateless apart from its RNG, so it is cheap to make one per
    generation run and impossible for two runs with the same seed to diverge."""

    def __init__(self, seed: int = 20260909):
        self.rng = random.Random(seed)
        self.fake = faker_for(seed)

    # -- helpers ------------------------------------------------------------------
    def pick(self, seq):
        return self.rng.choice(seq)

    def chance(self, p: float) -> bool:
        return self.rng.random() < p

    # -- locations ----------------------------------------------------------------
    def building_num(self) -> str:
        # Weighted towards the 1–4 digit house numbers a residential address actually has.
        style = self.rng.random()
        if style < 0.55:
            return str(self.rng.randint(1, 199))
        if style < 0.85:
            return str(self.rng.randint(200, 4999))
        if style < 0.95:
            return f"{self.rng.randint(1, 99)}{self.pick('ABC')}"
        return f"{self.rng.randint(1, 40)}-{self.rng.randint(100, 999)}"

    def street_name(self, abbreviate: bool | None = None) -> str:
        base = (
            self.pick(STREET_NAMES)
            if self.chance(0.75)
            else self.fake.last_name()
        )
        full_type = self.pick(STREET_TYPES_FULL)
        if abbreviate is None:
            abbreviate = self.chance(0.45)
        suffix = (
            self.pick(STREET_TYPE_ABBREV.get(full_type, [full_type]))
            if abbreviate
            else full_type
        )
        return f"{base} {suffix}"

    def address(self, abbreviate: bool | None = None) -> Rendered:
        """'24 Oak St' — two spans, BUILDINGNUM then STREET."""
        num = self.building_num()
        street = self.street_name(abbreviate)
        text = f"{num} {street}"
        return Rendered(
            text,
            parts=(
                (0, len(num), "BUILDINGNUM"),
                (len(num) + 1, len(text), "STREET"),
            ),
        )

    def street_only(self, abbreviate: bool | None = None) -> Rendered:
        return Rendered(self.street_name(abbreviate), entity="STREET")

    def city(self) -> Rendered:
        return Rendered(self.fake.city(), entity="CITY")

    def zipcode(self) -> Rendered:
        return Rendered(self.fake.postcode(), entity="ZIPCODE")

    def apartment(self) -> Rendered:
        unit = self.rng.choice(["apt", "apartment", "unit", "#"])
        num = self.rng.randint(1, 40) * self.rng.choice([1, 10, 100])
        text = f"{unit} {num}" if unit != "#" else f"#{num}"
        return Rendered(text, entity="BUILDINGNUM")

    # -- contact ------------------------------------------------------------------
    def phone(self) -> Rendered:
        area = self.rng.randint(200, 989)
        mid = self.rng.randint(200, 999)
        last = self.rng.randint(0, 9999)
        style = self.rng.random()
        if style < 0.35:
            text = f"{area}-{mid}-{last:04d}"
        elif style < 0.55:
            text = f"({area}) {mid}-{last:04d}"
        elif style < 0.75:
            text = f"{area}{mid}{last:04d}"
        elif style < 0.9:
            text = f"{area}.{mid}.{last:04d}"
        else:
            text = f"+1 {area} {mid} {last:04d}"
        return Rendered(text, entity="TELEPHONENUM")

    def email(self) -> Rendered:
        first = self.fake.first_name().lower()
        style = self.rng.random()
        domain = self.pick(["gmail.com", "hotmail.com", "yahoo.com", "outlook.com", "icloud.com"])
        if style < 0.4:
            local = f"{first}{self.rng.randint(1, 9999)}"
        elif style < 0.7:
            local = f"{first}.{self.fake.last_name().lower()}"
        elif style < 0.85:
            local = f"{first}_{self.pick(['xox', 'gamer', 'playz', 'official', 'real'])}"
        else:
            local = f"{self.pick(['xX', 'its', 'the'])}{first}{self.pick(['Xx', '__', '07'])}"
        return Rendered(f"{local}@{domain}", entity="EMAIL")

    def username(self) -> Rendered:
        # Not an OpenPII entity, so it carries no token label — but it is contact info
        # for the context head, which is what these templates are testing.
        base = self.fake.first_name().lower()
        return Rendered(
            f"@{base}{self.rng.choice(['', '_', '.'])}{self.rng.randint(1, 999)}"
        )

    # -- time ---------------------------------------------------------------------
    def clock_time(self) -> Rendered:
        hour = self.rng.randint(1, 12)
        minute = self.rng.choice([0, 0, 0, 15, 30, 30, 45, 5, 10, 20, 25, 40, 50])
        meridiem = self.pick(["", "", "pm", "PM", "am", " pm", "p.m."])
        style = self.rng.random()
        if minute == 0 and style < 0.4:
            text = f"{hour}{meridiem}"
        elif style < 0.7:
            text = f"{hour}:{minute:02d}{meridiem}"
        elif style < 0.85:
            # "530" — the informal form from §11.
            text = f"{hour}{minute:02d}" if minute else f"{hour}"
        else:
            text = f"{hour}.{minute:02d}{meridiem}"
        return Rendered(text.strip(), entity="TIME")

    def relative_time(self) -> Rendered:
        """'in 2 hrs', 'in like 20 min' — §23's relative-time adversarial case.

        Unlabelled: there is no clock time to point at, so the token head should stay
        quiet while the context head still fires `specific_time`.
        """
        n = self.rng.choice([1, 2, 2, 3, 20, 30, 45, 10, 15])
        unit = self.pick(["hours", "hrs", "hr", "minutes", "mins", "min"])
        lead = self.pick(["in", "in like", "in about", "for the next"])
        return Rendered(f"{lead} {n} {unit}")

    def day(self) -> Rendered:
        text = self.pick(DAYS) if self.chance(0.7) else self.pick(DAYS_SHORT)
        return Rendered(text, entity="DATE")

    def date(self) -> Rendered:
        """A short written date.

        The day-without-leading-zero form is built by stripping the zero rather than by
        asking strftime for it: `%-d` is a glibc extension that raises ValueError on
        Windows, and `%#d` is the MSVC spelling, so neither is portable. This slot had no
        callers until the generated templates arrived, which is why a corpus build had
        never hit it.
        """
        pattern = self.pick(["%m/%d", "%B %d", "%b %d"])
        text = self.fake.date(pattern=pattern)
        if pattern != "%m/%d" and self.chance(0.5):
            month, _, day = text.rpartition(" ")
            text = f"{month} {day.lstrip('0') or day}"
        return Rendered(text, entity="DATE")

    # -- people -------------------------------------------------------------------
    def given_name(self) -> Rendered:
        return Rendered(self.fake.first_name(), entity="GIVENNAME")

    def full_name(self) -> Rendered:
        first, last = self.fake.first_name(), self.fake.last_name()
        text = f"{first} {last}"
        return Rendered(
            text,
            parts=((0, len(first), "GIVENNAME"), (len(first) + 1, len(text), "SURNAME")),
        )

    # -- places (unlabelled: no OpenPII entity covers an organisation) ------------
    def school(self) -> Rendered:
        if self.chance(0.15):
            return Rendered(self.pick(SCHOOL_SHORT_FORMS))
        return Rendered(f"{self.pick(SCHOOL_PREFIXES)} {self.pick(SCHOOL_SUFFIXES)}")

    def activity(self) -> Rendered:
        return Rendered(self.pick(CLUBS_AND_ACTIVITIES))

    def public_place(self) -> Rendered:
        return Rendered(self.pick(PUBLIC_PLACES))

    def business(self) -> Rendered:
        return Rendered(self.pick(BUSINESS_NAMES))

    def relative(self) -> Rendered:
        return Rendered(self.pick(RELATIVES_OTHER))

    def guardian(self) -> Rendered:
        return Rendered(self.pick(GUARDIAN_TERMS))


SLOT_DISPATCH = {
    "ADDRESS": "address",
    "STREET": "street_only",
    "CITY": "city",
    "ZIP": "zipcode",
    "APT": "apartment",
    "PHONE": "phone",
    "EMAIL": "email",
    "USERNAME": "username",
    "TIME": "clock_time",
    "RELTIME": "relative_time",
    "DAY": "day",
    "DATE": "date",
    "NAME": "given_name",
    "FULLNAME": "full_name",
    "SCHOOL": "school",
    "ACTIVITY": "activity",
    "PLACE": "public_place",
    "BUSINESS": "business",
    "RELATIVE": "relative",
    "GUARDIAN": "guardian",
}
