"""Single-signal and general-safe template families (§9).

Two labelling conventions are settled here, because they are the ones a second person
writing templates would otherwise get wrong in a way nothing would catch:

**`child_ownership` is the "this is about me" flag, not a PII flag.** It is 1 whenever the
message discloses a location, contact point, schedule, or state *of the person typing*,
and 0 when the same information belongs to a third party or a public entity. That is what
makes it useful: it is the single bit that separates "I live at 24 Oak St" from "the pizza
place is at 24 Oak St", and both of those produce identical token-head output.

**`child_location` does not mean *exact* location.** It means a location in the message is
where the child is, lives, or will be. Precision is the token head's job — STREET plus
BUILDINGNUM is an address, CITY alone is not — and the risk engine reads both. Conflating
them here would force the context head to relearn what the token head already knows, from
less data.

Families are the holdout unit. Keep a family narrow enough that holding it out removes a
*pattern* rather than a whole capability: `loc.home.declare` and `loc.home.currently` are
separate families so that testing on one is not testing on a paraphrase of the other.
"""

from __future__ import annotations

from generators.render import Template
from keyguard_ml.labels import CONTEXT_LABELS


def L(**kw: int) -> dict[str, int]:
    labels = {k: 0 for k in CONTEXT_LABELS}
    for key, value in kw.items():
        if key not in labels:
            raise KeyError(f"unknown context label {key!r}")
        labels[key] = value
    return labels


# ======================================================================================
# Exact location — the child's own
# ======================================================================================

LOCATION_TEMPLATES: list[Template] = [
    Template("loc.home.declare", "I live at {ADDRESS}.", L(child_ownership=1, child_location=1), weight=1.4),
    Template("loc.home.declare", "my address is {ADDRESS}", L(child_ownership=1, child_location=1), weight=1.4),
    Template("loc.home.declare", "we live at {ADDRESS}", L(child_ownership=1, child_location=1)),
    Template("loc.home.declare", "our house is {ADDRESS}", L(child_ownership=1, child_location=1)),
    Template("loc.home.declare", "I stay at {ADDRESS}", L(child_ownership=1, child_location=1)),

    Template("loc.home.full", "I live at {ADDRESS}, {CITY}", L(child_ownership=1, child_location=1)),
    Template("loc.home.full", "my address is {ADDRESS} {CITY} {ZIP}", L(child_ownership=1, child_location=1)),
    Template("loc.home.full", "{ADDRESS}, {CITY} {ZIP} is where I live", L(child_ownership=1, child_location=1)),
    Template("loc.home.full", "I'm at {ADDRESS} {APT}", L(child_ownership=1, child_location=1)),

    Template("loc.home.currently", "I'm at {ADDRESS} right now", L(child_ownership=1, child_location=1)),
    Template("loc.home.currently", "im at {ADDRESS}", L(child_ownership=1, child_location=1), weight=1.3),
    Template("loc.home.currently", "currently at {ADDRESS}", L(child_ownership=1, child_location=1)),
    Template("loc.home.currently", "I'm sitting outside {ADDRESS}", L(child_ownership=1, child_location=1)),

    Template("loc.home.directions", "just go to {ADDRESS} and I'll be there", L(child_ownership=1, child_location=1, meetup=1)),
    Template("loc.home.directions", "its the house at {ADDRESS}", L(child_ownership=1, child_location=1)),
    Template("loc.home.directions", "I'm the blue house on {STREET}", L(child_ownership=1, child_location=1)),
    Template("loc.home.directions", "my place is on {STREET} near {PLACE}", L(child_ownership=1, child_location=1)),

    Template("loc.city.only", "I live in {CITY}", L(child_ownership=1, child_location=1)),
    Template("loc.city.only", "im from {CITY}", L(child_ownership=1, child_location=1)),
    Template("loc.city.only", "we moved to {CITY} last year", L(child_ownership=1, child_location=1)),

    Template("loc.public.currently", "I'm at {PLACE}", L(child_ownership=1, child_location=1)),
    Template("loc.public.currently", "im waiting at {PLACE}", L(child_ownership=1, child_location=1)),
    Template("loc.public.currently", "at {PLACE} rn", L(child_ownership=1, child_location=1)),
    Template("loc.public.currently", "gonna be at {PLACE} in a bit", L(child_ownership=1, child_location=1)),
]

# ======================================================================================
# Contact information
# ======================================================================================

CONTACT_TEMPLATES: list[Template] = [
    Template("contact.phone.own", "my number is {PHONE}", L(child_ownership=1), weight=1.4),
    Template("contact.phone.own", "text me at {PHONE}", L(child_ownership=1)),
    Template("contact.phone.own", "you can call me on {PHONE}", L(child_ownership=1)),
    Template("contact.phone.own", "heres my number {PHONE}", L(child_ownership=1)),
    Template("contact.phone.own", "my cell is {PHONE}", L(child_ownership=1)),
    Template("contact.phone.own", "add me {PHONE}", L(child_ownership=1)),

    Template("contact.email.own", "my email is {EMAIL}", L(child_ownership=1), weight=1.3),
    Template("contact.email.own", "email me at {EMAIL}", L(child_ownership=1)),
    Template("contact.email.own", "send it to {EMAIL}", L(child_ownership=1)),
    Template("contact.email.own", "{EMAIL} is my email", L(child_ownership=1)),

    Template("contact.handle.own", "my insta is {USERNAME}", L(child_ownership=1)),
    Template("contact.handle.own", "add me on snap {USERNAME}", L(child_ownership=1)),
    Template("contact.handle.own", "dm me {USERNAME}", L(child_ownership=1)),

    Template("contact.name.own", "my full name is {FULLNAME}", L(child_ownership=1)),
    Template("contact.name.own", "im {FULLNAME} btw", L(child_ownership=1)),
]

# ======================================================================================
# School context
# ======================================================================================

SCHOOL_TEMPLATES: list[Template] = [
    Template("school.attend", "I go to {SCHOOL}", L(child_ownership=1, school_context=1), weight=1.4),
    Template("school.attend", "im at {SCHOOL}", L(child_ownership=1, school_context=1, child_location=1)),
    Template("school.attend", "I'm in grade 7 at {SCHOOL}", L(child_ownership=1, school_context=1)),
    Template("school.attend", "my school is {SCHOOL}", L(child_ownership=1, school_context=1)),
    Template("school.attend", "we go to {SCHOOL}", L(child_ownership=1, school_context=1)),

    Template("school.located", "{SCHOOL} is right by {PLACE}", L(school_context=1)),
    Template("school.located", "my school is on {STREET}", L(child_ownership=1, school_context=1, child_location=1)),
    Template("school.located", "{SCHOOL} is at {ADDRESS}", L(school_context=1)),

    Template("school.activity", "I have {ACTIVITY} at {SCHOOL}", L(child_ownership=1, school_context=1)),
    Template("school.activity", "{ACTIVITY} is at {SCHOOL} today", L(child_ownership=1, school_context=1)),
    Template("school.activity", "im in {ACTIVITY} at school", L(child_ownership=1, school_context=1)),
]

# ======================================================================================
# Routine — predictable, repeated behaviour
# ======================================================================================

ROUTINE_TEMPLATES: list[Template] = [
    Template("routine.walk", "I walk home through {PLACE} every day", L(child_ownership=1, child_location=1, routine=1), weight=1.3),
    Template("routine.walk", "I walk past {PLACE} every {DAY}", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.walk", "I always cut through {PLACE} after school", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.walk", "same route every day past {PLACE}", L(child_ownership=1, child_location=1, routine=1)),

    Template("routine.bus", "my bus drops me at {PLACE} every day", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.bus", "I catch the bus at {PLACE} every morning", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.bus", "I get picked up at {PLACE} every {DAY}", L(child_ownership=1, child_location=1, routine=1)),

    Template("routine.activity", "I have {ACTIVITY} every {DAY}", L(child_ownership=1, routine=1), weight=1.2),
    Template("routine.activity", "{ACTIVITY} is every {DAY}", L(child_ownership=1, routine=1)),
    Template("routine.activity", "I go to {ACTIVITY} every week", L(child_ownership=1, routine=1)),
    Template("routine.activity", "every {DAY} I have {ACTIVITY}", L(child_ownership=1, routine=1)),

    Template("routine.place", "im always at {PLACE} on weekends", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.place", "I go to {PLACE} every {DAY}", L(child_ownership=1, child_location=1, routine=1)),
    Template("routine.place", "same place every {DAY} after school", L(child_ownership=1, routine=1)),
]

# ======================================================================================
# Specific time
# ======================================================================================

TIME_TEMPLATES: list[Template] = [
    Template("time.ends", "{ACTIVITY} ends at {TIME}", L(child_ownership=1, specific_time=1), weight=1.3),
    Template("time.ends", "im done at {TIME}", L(child_ownership=1, specific_time=1)),
    Template("time.ends", "I finish at {TIME}", L(child_ownership=1, specific_time=1)),
    Template("time.ends", "{ACTIVITY} is over at {TIME}", L(child_ownership=1, specific_time=1)),

    Template("time.starts", "{ACTIVITY} starts at {TIME}", L(child_ownership=1, specific_time=1)),
    Template("time.starts", "I have to be there by {TIME}", L(child_ownership=1, specific_time=1)),
    Template("time.starts", "gotta leave at {TIME}", L(child_ownership=1, specific_time=1)),

    Template("time.relative", "ill be free {RELTIME}", L(child_ownership=1, specific_time=1)),
    Template("time.relative", "im home {RELTIME}", L(child_ownership=1, child_location=1, specific_time=1)),
    Template("time.relative", "leaving {RELTIME}", L(child_ownership=1, specific_time=1)),
]

# ======================================================================================
# Alone
# ======================================================================================

ALONE_TEMPLATES: list[Template] = [
    Template("alone.home", "im home alone", L(child_ownership=1, alone=1), weight=1.5),
    Template("alone.home", "I'm home alone tonight", L(child_ownership=1, alone=1), weight=1.4),
    Template("alone.home", "home by myself today", L(child_ownership=1, alone=1)),
    Template("alone.home", "im by myself rn", L(child_ownership=1, alone=1)),
    Template("alone.home", "nobody else is here", L(child_ownership=1, alone=1, guardian_absent=1)),

    Template("alone.house_to_myself", "got the house to myself tonight", L(child_ownership=1, alone=1, guardian_absent=1), weight=1.3, tags=("adversarial",)),
    Template("alone.house_to_myself", "house is empty", L(child_ownership=1, alone=1, guardian_absent=1), tags=("adversarial",)),
    Template("alone.house_to_myself", "whole place to myself", L(child_ownership=1, alone=1, guardian_absent=1), tags=("adversarial",)),
    Template("alone.house_to_myself", "no ones home rn", L(child_ownership=1, alone=1, guardian_absent=1), tags=("adversarial",)),

    Template("alone.walking", "im walking home alone", L(child_ownership=1, alone=1, child_location=1)),
    Template("alone.walking", "waiting at {PLACE} by myself", L(child_ownership=1, alone=1, child_location=1)),
    Template("alone.walking", "im at {PLACE} alone", L(child_ownership=1, alone=1, child_location=1)),
]

# ======================================================================================
# Guardian absent
# ======================================================================================

GUARDIAN_TEMPLATES: list[Template] = [
    Template("guardian.away.time", "{GUARDIAN} aren't home until {TIME}", L(child_ownership=1, guardian_absent=1, specific_time=1), weight=1.4),
    Template("guardian.away.time", "{GUARDIAN} won't be back til {TIME}", L(child_ownership=1, guardian_absent=1, specific_time=1)),
    Template("guardian.away.time", "{GUARDIAN} gets home at {TIME}", L(child_ownership=1, guardian_absent=1, specific_time=1)),
    Template("guardian.away.time", "{GUARDIAN} is back {RELTIME}", L(child_ownership=1, guardian_absent=1, specific_time=1), tags=("adversarial",)),

    Template("guardian.away.plain", "{GUARDIAN} is out", L(child_ownership=1, guardian_absent=1)),
    Template("guardian.away.plain", "{GUARDIAN} went out", L(child_ownership=1, guardian_absent=1)),
    Template("guardian.away.plain", "{GUARDIAN} is at work", L(child_ownership=1, guardian_absent=1)),
    Template("guardian.away.plain", "{GUARDIAN} are away this weekend", L(child_ownership=1, guardian_absent=1)),
    Template("guardian.away.plain", "{GUARDIAN} isnt here", L(child_ownership=1, guardian_absent=1)),

    Template("guardian.away.indirect", "{GUARDIAN} will never know", L(child_ownership=1, guardian_absent=1), tags=("adversarial",)),
    Template("guardian.away.indirect", "{GUARDIAN} left me here", L(child_ownership=1, guardian_absent=1, alone=1)),
    Template("guardian.away.indirect", "im not supervised today", L(child_ownership=1, guardian_absent=1), tags=("adversarial",)),
]

# ======================================================================================
# Meetup
# ======================================================================================

MEETUP_TEMPLATES: list[Template] = [
    Template("meetup.invite.home", "come over", L(child_ownership=1, meetup=1)),
    Template("meetup.invite.home", "you should come to my house", L(child_ownership=1, meetup=1, child_location=1)),
    Template("meetup.invite.home", "wanna come over later", L(child_ownership=1, meetup=1)),

    Template("meetup.meet.place", "meet me at {PLACE}", L(child_ownership=1, meetup=1, child_location=1), weight=1.3),
    Template("meetup.meet.place", "lets meet at {PLACE}", L(child_ownership=1, meetup=1, child_location=1)),
    Template("meetup.meet.place", "ill meet you outside {PLACE}", L(child_ownership=1, meetup=1, child_location=1)),
    Template("meetup.meet.place", "meet me behind {SCHOOL}", L(child_ownership=1, meetup=1, child_location=1, school_context=1), weight=1.3),

    Template("meetup.agree", "ok ill be there", L(child_ownership=1, meetup=1)),
    Template("meetup.agree", "yeah lets do it, ill come", L(child_ownership=1, meetup=1)),
    Template("meetup.agree", "sure I can meet up", L(child_ownership=1, meetup=1)),

    Template("meetup.alone_hint", "come alone", L(meetup=1), tags=("adversarial",)),
    Template("meetup.alone_hint", "dont tell anyone but come over", L(child_ownership=1, meetup=1), tags=("adversarial",)),

    # Added after the first full build reported `meetup` at 62 positives in validation —
    # too few to fit a threshold on. More families give the splitter something to
    # distribute; a single family cannot be in two splits at once.
    Template("meetup.propose.time", "wanna hang out at {TIME}", L(child_ownership=1, meetup=1, specific_time=1)),
    Template("meetup.propose.time", "free at {TIME}? we could meet", L(child_ownership=1, meetup=1, specific_time=1)),
    Template("meetup.propose.day", "you free {DAY}? lets meet up", L(child_ownership=1, meetup=1)),
    Template("meetup.propose.day", "wanna do something {DAY}", L(child_ownership=1, meetup=1)),
    Template("meetup.pickup", "can you pick me up from {PLACE}", L(child_ownership=1, meetup=1, child_location=1)),
    Template("meetup.pickup", "come get me at {PLACE}", L(child_ownership=1, meetup=1, child_location=1)),
    Template("meetup.where", "where should we meet", L(meetup=1)),
    Template("meetup.where", "what time are we meeting", L(meetup=1, specific_time=1)),
]

# ======================================================================================
# General safe conversation (§13's 10k of ordinary messages)
# ======================================================================================
#
# Without these the model never sees the distribution it will spend 99% of its life on,
# and every metric that matters — false warnings per 1,000 safe messages — is measured on
# a slice the training set barely contained.

SAFE_TEMPLATES: list[Template] = [
    Template("safe.chat", "hey whats up", L()),
    Template("safe.chat", "lol that was so funny", L()),
    Template("safe.chat", "did you see the game last night", L()),
    Template("safe.chat", "im so tired today", L()),
    Template("safe.chat", "what are you doing", L()),
    Template("safe.chat", "nothing much you", L()),
    Template("safe.chat", "that test was so hard", L()),
    Template("safe.chat", "i cant believe that happened", L()),
    Template("safe.chat", "brb", L()),
    Template("safe.chat", "ok sounds good", L()),

    Template("safe.homework", "did you do the math homework", L()),
    Template("safe.homework", "what page was the reading", L()),
    Template("safe.homework", "i forgot my science book", L()),
    Template("safe.homework", "the project is due friday", L()),
    Template("safe.homework", "can i copy your notes", L()),

    Template("safe.media", "have you watched that new show", L()),
    Template("safe.media", "that song is stuck in my head", L()),
    Template("safe.media", "im playing minecraft rn", L()),
    Template("safe.media", "wanna play later", L()),
    Template("safe.media", "my brother beat the whole game", L()),

    Template("safe.feelings", "im kinda annoyed", L()),
    Template("safe.feelings", "that made me so happy", L()),
    Template("safe.feelings", "i miss you", L()),
    Template("safe.feelings", "im excited for the weekend", L()),
    Template("safe.feelings", "im nervous about tomorrow", L()),

    Template("safe.thirdparty.chat", "{RELATIVE} said the same thing", L()),
    Template("safe.thirdparty.chat", "{RELATIVE} is so annoying", L()),
    Template("safe.thirdparty.chat", "{NAME} said they might come", L()),
    Template("safe.thirdparty.chat", "{NAME} and {NAME} had a fight", L()),
]

SINGLE_SIGNAL_TEMPLATES: list[Template] = [
    *LOCATION_TEMPLATES,
    *CONTACT_TEMPLATES,
    *SCHOOL_TEMPLATES,
    *ROUTINE_TEMPLATES,
    *TIME_TEMPLATES,
    *ALONE_TEMPLATES,
    *GUARDIAN_TEMPLATES,
    *MEETUP_TEMPLATES,
]

ALL_BASE_TEMPLATES: list[Template] = [*SINGLE_SIGNAL_TEMPLATES, *SAFE_TEMPLATES]
