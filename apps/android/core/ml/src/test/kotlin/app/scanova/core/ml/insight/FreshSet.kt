package app.scanova.core.ml.insight

/**
 * A third set, written last and never used to change the rules: the honest
 * "how would it do on something new" number. It has no gate; its misses are
 * the to-do list for the next rule change (move a sample to [EVALUATION_SET]
 * when a rule is changed for it, and write new fresh ones). Fake data.
 */
internal val FRESH_SET: List<Sample> = listOf(
    Sample(
        "supermarket slip with net payable",
        DocumentType.RECEIPT,
        "Receipt – Chase Up – 15 Nov 2026",
        """
        CHASE UP
        Gulshan-e-Iqbal Block 13
        STRN: 32-77-8761-123-45
        Inv No: 0091-221 Counter: 5
        Date: 15-11-2026 19:02
        Cooking Oil 5L 2,950.00
        Tea 950g 1,650.00
        Detergent 1kg 690.00
        Items: 3
        Net Payable 5,290.00
        Received 6,000.00
        Return 710.00
        """,
    ),
    Sample(
        "software subscription invoice",
        DocumentType.INVOICE,
        "Invoice – Notion Labs, Inc. – 31 Oct 2026",
        """
        Invoice
        Invoice number 8F2A61C4-0007
        Date of issue October 31, 2026
        Date due November 14, 2026
        Notion Labs, Inc.
        2300 Harrison Street, San Francisco
        Bill to
        Ayesha Khan
        Plus plan, Oct 31 – Nov 30, 2026 $10.00
        Amount due $10.00
        """,
    ),
    Sample(
        "gas bill in capitals",
        DocumentType.BILL,
        "Bill – Sui Southern Gas Company Limited – Oct 2026",
        """
        SUI SOUTHERN GAS COMPANY LIMITED
        CUSTOMER NO. 1234567890
        BILLING MONTH OCT-2026
        METER NO. G4-778812
        GAS CONSUMED 2.1 HM3
        CURRENT BILL AMOUNT 1,980
        DUE DATE 12-NOV-2026
        """,
    ),
    Sample(
        "duplicate electricity bill",
        DocumentType.BILL,
        "Bill – Faisalabad Electric Supply Company – Jun 2026",
        """
        FAISALABAD ELECTRIC SUPPLY COMPANY
        DUPLICATE BILL
        REF NO 12 13456 1234567 R
        NAME: MUHAMMAD ASLAM
        BILL MONTH JUN 26
        UNITS CONSUMED 520
        PAYABLE WITHIN DUE DATE 31,250
        """,
    ),
    Sample(
        "US driver license",
        DocumentType.ID_CARD,
        "Driving licence",
        """
        CALIFORNIA
        DRIVER LICENSE
        DL I1234568
        EXP 08/31/2029
        LN SAMPLE
        FN ALEXANDER JOSEPH
        DOB 08/31/1977
        SEX M HGT 5-10 EYES BRN
        """,
    ),
    Sample(
        "vaccination certificate with an ID number",
        DocumentType.CERTIFICATE,
        "COVID-19 Vaccination Certificate",
        """
        Ministry of National Health Services
        COVID-19 Vaccination Certificate
        Name: Hamid Ali
        CNIC: 35202-9876543-1
        Vaccine: Pfizer Dose 1: 12 Jun 2021 Dose 2: 03 Jul 2021
        Certificate ID: PK-NIMS-7781234
        """,
    ),
    Sample(
        "school leave application",
        DocumentType.LETTER,
        "Letter – Application for Leave",
        """
        The Principal
        Beaconhouse School System
        Lahore
        Subject: Application for Leave
        Respected Madam,
        With due respect, I beg to state that I am suffering from fever and cannot attend school
        from 17 to 19 November 2026. Kindly grant me leave for three days.
        Yours obediently,
        Ali Raza
        Class 8-B
        """,
    ),
    Sample(
        "email printout",
        DocumentType.LETTER,
        "Letter – Project timeline",
        """
        From: Sara Ahmed <sara@example.com>
        Sent: Monday, November 9, 2026 10:14 AM
        To: Bilal Khan
        Subject: Project timeline
        Hi Bilal,
        Please find attached the updated project timeline. Let me know if the dates work for you.
        Thanks,
        Sara
        """,
    ),
    Sample(
        "bank deposit slip",
        DocumentType.FORM,
        "Cash Deposit Slip",
        """
        MEEZAN BANK
        CASH DEPOSIT SLIP
        Date: ____________
        Account Title: ______________________
        Account No: ______________________
        Amount in Words: ______________________
        Depositor's Name: ______________
        Depositor's Signature: ______________
        """,
    ),
    Sample(
        "UK bank statement without opening and closing balance",
        DocumentType.BANK_STATEMENT,
        "Bank statement – Barclays – Oct 2026",
        """
        Barclays
        Statement
        Sort code 20-00-00 Account number 12345678
        1 October to 31 October 2026
        Date Description Money out Money in Balance
        01 Oct Start balance 2,450.00
        04 Oct Card payment TESCO 45.20 2,404.80
        11 Oct Salary 1,800.00 4,204.80
        28 Oct Direct debit COUNCIL TAX 120.00 4,084.80
        31 Oct End balance 4,084.80
        """,
    ),
    Sample(
        "prescription with abbreviations",
        DocumentType.PRESCRIPTION,
        "Prescription – Dr. Naeem Akhtar – 12 Nov 2026",
        """
        DR. NAEEM AKHTAR
        M.B.B.S (Pb), F.C.P.S
        Medical Specialist
        Pt: Farhan Age/Sex: 34/M 12-11-26
        1) T. Augmentin 1g 1x2 x5d
        2) T. Risek 40mg 1x1 (before breakfast)
        3) Syp. Ventolin 2tsp TDS
        """,
    ),
    Sample(
        "cricket match ticket",
        DocumentType.TICKET,
        "Ticket – Pakistan Super League 2026 – 21 Nov 2026",
        """
        PAKISTAN SUPER LEAGUE 2026
        Lahore Qalandars vs Karachi Kings
        Gaddafi Stadium, Lahore
        Enclosure: Imran Khan Row: K Seat: 118
        Match Date: 21 Nov 2026 Gates Open: 6:00 PM
        Ticket Price: PKR 2,500
        """,
    ),
    Sample(
        "ride-hailing receipt",
        DocumentType.RECEIPT,
        "Receipt – Careem – 14 Nov 2026",
        """
        Careem
        Thanks for riding with Careem
        Trip on 14 November 2026
        From: DHA Phase 6
        To: Lahore Airport
        Distance 18.4 km Duration 32 min
        Fare breakdown
        Base fare PKR 150
        Distance PKR 820
        Total PKR 970
        Paid by Card
        """,
    ),
    Sample(
        "CV",
        DocumentType.OTHER,
        "Ayesha Khan",
        """
        AYESHA KHAN
        Software Engineer
        ayesha.khan@example.com | +92 300 0000000 | Lahore
        EXPERIENCE
        Senior Developer, Systems Ltd (2022 – present)
        EDUCATION
        BS Computer Science, FAST NUCES (2018 – 2022)
        SKILLS
        Kotlin, Android, PostgreSQL
        """,
    ),
    Sample(
        "page from a book",
        DocumentType.OTHER,
        "Chapter 3",
        """
        Chapter 3
        The Monsoon
        The rain came early that year, and with it the smell of wet earth that my grandmother
        said was the city breathing again after the long summer.
        """,
    ),
).map { it.copy(text = it.text.trimIndent()) }
