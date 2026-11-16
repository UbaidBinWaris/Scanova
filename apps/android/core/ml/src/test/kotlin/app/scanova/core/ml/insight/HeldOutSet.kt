package app.scanova.core.ml.insight

/**
 * A second set, written after the rules and never used to tune them before
 * its first measurement: different shops, layouts and wording, to show how
 * the rules do on documents they were not written for. Its numbers are
 * reported separately from [EVALUATION_SET]'s (which the rules were tuned on).
 * Fake data throughout.
 */
internal val HELD_OUT_SET: List<Sample> = listOf(
    Sample(
        "sweets shop bill",
        DocumentType.RECEIPT,
        "Receipt – Bundu Khan Sweets & Bakers – 13 Nov 2026",
        """
        Bundu Khan Sweets & Bakers
        F-7 Markaz Islamabad
        Bill No. 30418 Date 13/11/2026
        Gulab Jamun 1 kg 1,200
        Nan Khatai 500 g 650
        Grand Total 1,850
        Paid Cash 2,000
        Balance 150
        *** Thank You ***
        """,
    ),
    Sample(
        "UK supermarket receipt without the word receipt",
        DocumentType.RECEIPT,
        "Receipt – Tesco – 5 Nov 2026",
        """
        TESCO
        Superstore
        VAT No. GB 220 4302 31
        MILK SEMI SKIMMED 2PT £1.45
        BANANAS LOOSE £0.98
        BREAD WHITE 800G £0.75
        BALANCE DUE £3.18
        VISA £3.18
        CHANGE £0.00
        05/11/26 14:32 ST:2451 OP:118
        """,
    ),
    Sample(
        "pharmacy sale invoice",
        DocumentType.RECEIPT,
        "Receipt – D. Watson Chemist – 12 Nov 2026",
        """
        D. WATSON CHEMIST
        Blue Area Islamabad
        SALE INVOICE
        Inv# 552190 12-Nov-2026 11:20 AM
        Risek 20mg Cap x 14 546.00
        Surbex Z Tab x 30 690.00
        Total Items: 2
        Net Amount 1,236.00
        Cash Received 1,500.00
        Change 264.00
        """,
    ),
    Sample(
        "design studio invoice with bill from and bill to",
        DocumentType.INVOICE,
        "Invoice – Pixel Perfect Studio – 2 Nov 2026",
        """
        Invoice
        Bill from: Pixel Perfect Studio
        Bill to: Ahmed Traders
        Issued: November 2, 2026
        Due: November 16, 2026
        Item Hours Rate Amount
        UI design 20 3,000 60,000
        Prototype 10 3,000 30,000
        Total 90,000
        Please pay via bank transfer to Account 0123456789
        """,
    ),
    Sample(
        "sales tax invoice",
        DocumentType.INVOICE,
        "Invoice – Shaheen Trading Company – 30 Oct 2026",
        """
        SHAHEEN TRADING COMPANY
        Sales Tax Invoice
        S.No. 1182
        Date: 30-10-2026
        Buyer's Name: Rehman Garments
        Buyer's NTN: 7654321-0
        Description: Polyester Yarn 150D
        Quantity: 500 kg
        Value Excl. Tax: 425,000
        Sales Tax @18%: 76,500
        Value Incl. Tax: 501,500
        """,
    ),
    Sample(
        "mobile postpaid bill",
        DocumentType.BILL,
        "Bill – Jazz – Oct 2026",
        """
        Jazz
        Postpaid Bill
        Customer Name: Usman Tariq
        Mobile Number: 0300-1234567
        Bill Period: 01-Oct-2026 to 31-Oct-2026
        Monthly Line Rent 1,000.00
        Usage Charges 645.00
        Taxes 411.25
        Total Amount Due 2,056.25
        Pay By: 15-Nov-2026
        """,
    ),
    Sample(
        "electricity bill read with l for I throughout",
        DocumentType.BILL,
        "Bill – Islamabad Electric Supply Company – Sep 2026",
        """
        lSLAMABAD ELECTRlC SUPPLY COMPANY
        IESCO
        CONSUMER lD 0123456789012
        BlLL MONTH: SEP-2026
        UNlTS CONSUMED: 410
        CURRENT BlLL 14,880
        PAYABLE BY DUE DATE 14,880
        """,
    ),
    Sample(
        "internet provider's monthly invoice",
        DocumentType.BILL,
        "Bill – Nayatel – Nov 2026",
        """
        Nayatel
        Monthly Invoice
        Customer ID: NT-88213
        Service: Fiber 50 Mbps
        Billing Period: 1 Nov 2026 - 30 Nov 2026
        Monthly Charges 4,500
        Tax 675
        Total 5,175
        """,
    ),
    Sample(
        "back of a national identity card",
        DocumentType.ID_CARD,
        "ID card",
        """
        35202-1234567-8
        Present Address: House 12, Street 4, Model Town, Lahore
        Permanent Address: Same as above
        Registrar General of Pakistan
        """,
    ),
    Sample(
        "foreign resident identity card",
        DocumentType.ID_CARD,
        "ID card – Imran Aslam",
        """
        United Arab Emirates
        Resident Identity Card
        ID Number 784-1990-1234567-1
        Name: Imran Aslam
        Nationality: Pakistan
        Date of Birth: 01/05/1990
        Expiry Date: 12/03/2028
        """,
    ),
    Sample(
        "certificate of appreciation",
        DocumentType.CERTIFICATE,
        "Certificate of Appreciation – Kashif Raza",
        """
        Certificate of Appreciation
        is awarded to
        Mr. Kashif Raza
        in appreciation of his valuable contribution as a volunteer
        at the Clean Karachi Drive 2026.
        Karachi, 2 November 2026
        """,
    ),
    Sample(
        "school character certificate",
        DocumentType.CERTIFICATE,
        "Character Certificate – Ahmad Bilal",
        """
        GOVT. HIGH SCHOOL NO. 1 SAHIWAL
        CHARACTER CERTIFICATE
        It is certified that Ahmad Bilal son of Muhammad Bilal
        was a bonafide student of this school. He bears a good moral character.
        Headmaster
        Dated: 10-11-2026
        """,
    ),
    Sample(
        "resignation letter",
        DocumentType.LETTER,
        "Letter – Resignation",
        """
        14 November 2026
        To,
        The HR Manager
        Systems Limited, Lahore
        Subject: Resignation
        Respected Sir,
        I hereby resign from my position as Accounts Officer with effect from 30 November 2026.
        I am thankful for the opportunities provided to me during my tenure.
        Sincerely,
        Hina Javed
        """,
    ),
    Sample(
        "recommendation letter",
        DocumentType.LETTER,
        "Letter – Northfield University – 3 Nov 2026",
        """
        Northfield University
        Office of the Registrar
        November 3, 2026
        Dear Admissions Committee,
        It is my pleasure to recommend Ms. Sana Iqbal for your graduate program.
        She was among the top students in my class and showed remarkable dedication.
        Please feel free to contact me for any further information.
        Best regards,
        Prof. David Miller
        """,
    ),
    Sample(
        "job application form without the word form",
        DocumentType.FORM,
        "Application for Employment",
        """
        APPLICATION FOR EMPLOYMENT
        Position Applied For: ______________
        Full Name: ______________
        CNIC: ______________
        Date of Birth: ______________
        Qualification: ______________
        Experience (years): ______________
        Expected Salary: ______________
        Signature: ______________ Date: ______________
        """,
    ),
    Sample(
        "change of address request",
        DocumentType.FORM,
        "Change of Address Request",
        """
        Change of Address Request
        Customer No: __________
        Old Address: _______________________
        New Address: _______________________
        Effective Date: __________
        Customer Signature: __________
        """,
    ),
    Sample(
        "statement of account",
        DocumentType.BANK_STATEMENT,
        "Bank statement – Bank Alfalah – Oct 2026",
        """
        Bank Alfalah
        Statement of Account
        Account Number: 0012-1004567890
        Statement Period: 01 Oct 2026 to 31 Oct 2026
        Opening Balance 145,000.00
        Closing Balance 98,250.00
        """,
    ),
    Sample(
        "mobile wallet statement",
        DocumentType.BANK_STATEMENT,
        "Bank statement – JazzCash – Oct 2026",
        """
        JazzCash
        Account Statement
        Mobile Account: 0300-1234567
        From: 01/10/2026 To: 31/10/2026
        01/10/2026 Money Received from Ali 5,000.00
        05/10/2026 Bill Payment LESCO 3,200.00
        11/10/2026 Mobile Load 500.00
        20/10/2026 Money Sent to Sara 1,000.00
        """,
    ),
    Sample(
        "hospital discharge medication list",
        DocumentType.PRESCRIPTION,
        "Prescription – Aga Khan University Hospital – 11 Nov 2026",
        """
        AGA KHAN UNIVERSITY HOSPITAL
        Patient: Nadia Akram MR# 445566
        Date: 11/11/2026
        Medications:
        Amoxicillin 500 mg capsule - 1 capsule three times a day for 7 days
        Paracetamol 500 mg tablet - as needed for fever
        Signed: Dr. Farhan Ali
        """,
    ),
    Sample(
        "spectacle prescription",
        DocumentType.PRESCRIPTION,
        "Prescription – Vision Care Optical – 8 Nov 2026",
        """
        Vision Care Optical
        Spectacle Prescription
        Name: Zara Shah Date: 08/11/2026
        SPH CYL AXIS
        RE -1.25 -0.50 180
        LE -1.00 -0.25 170
        Add: +1.00
        Optometrist: Hira Saleem
        """,
    ),
    Sample(
        "boarding pass without a year",
        DocumentType.TICKET,
        "Ticket – airblue",
        """
        airblue
        Boarding Pass
        NAME: RAZA/ALI MR
        FROM LAHORE TO KARACHI
        FLIGHT PA 401 DATE 24NOV SEAT 7A
        BOARDING 09:15 GATE 5
        """,
    ),
    Sample(
        "concert ticket",
        DocumentType.TICKET,
        "Ticket – Live in Concert – 28 Nov 2026",
        """
        LIVE IN CONCERT
        Atif Aslam
        Expo Centre Lahore
        Saturday, 28 November 2026 8:00 PM
        General Admission
        Ticket ID: AA-2026-77812
        Gates open at 6:30 PM
        """,
    ),
    Sample(
        "warranty card",
        DocumentType.OTHER,
        "Dawlance",
        """
        Dawlance
        Warranty Card
        Product: Refrigerator 9178 LF
        Serial No: DL9178-22981
        Date of Purchase: 03/11/2026
        Warranty Period: 10 years compressor
        Dealer Stamp
        """,
    ),
    Sample(
        "residents' notice",
        DocumentType.OTHER,
        "Notice",
        """
        NOTICE
        All residents are informed that water supply will remain suspended on
        Sunday, 22 November 2026 from 9 AM to 5 PM due to maintenance work.
        Management
        Bahria Town Phase 4
        """,
    ),
    Sample(
        "shopping list",
        DocumentType.OTHER,
        "Shopping list",
        """
        Shopping list
        eggs
        milk 2
        atta 10kg
        soap
        """,
    ),
    Sample(
        "wedding invitation",
        DocumentType.OTHER,
        "Mr. & Mrs. Tariq Mehmood",
        """
        Mr. & Mrs. Tariq Mehmood
        request the pleasure of your company
        at the wedding reception of their son
        Hamza
        with
        Ayesha
        on Saturday, 5 December 2026 at 7:00 PM
        Venue: Royal Palm Golf & Country Club, Lahore
        """,
    ),
).map { it.copy(text = it.text.trimIndent()) }
