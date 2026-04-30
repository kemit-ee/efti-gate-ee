# Users and roles

There are three main groups of users around one national gate:

- **Gate Super Admin** – operates and configures all the connections and accesses of a gate, including other gates, platforms, authorities.
- **Platform user** – usually a private business entity that runs an eFTI Platform that holds datasets.
- **Authority user** – works for an authority that is allowed to request datasets.

Each of these users sees a different side of the same system.

## Authentication

* Users in the system can be super admins, or be restricted to another Gate, Platform or Authority.
* Restricted admin users can access the Admin UI and configure only their respectable entities, including adding of more users.
* A user has a system-generated password that is shown only once on user creation
    * Admin UI access uses Browser's Basic Auth, with email and password
    * API access involves a Bearer token, composed of user's id and password
    * Passwords are stored in the database in hashed form, and thus can only be seen once on initial generation

Giving technical access to Platform or Authority always requires creating a user and authenticating with a Bearer token.

# Data protection
In the context of eFTI, data protection is ensured through data minimization, controlled access, and local storage.
The eFTI Gate stores only identifiers related to consignments, without retaining full consignment details,
thereby minimizing the amount of personal or sensitive data held centrally.
When authorities request data, they do so via subsets, which define only the specific parts of the dataset legally required.
This ensures that only necessary information is provided, in line with the principle of data minimization under GDPR.

Access to identifiers and subsets is strictly limited to competent authorities with the appropriate access rights,
ensuring that data is shared solely with authorized parties.
Full consignment information remains stored on the respective platforms of the parties involved,
keeping data processing local and avoiding unnecessary centralization.
This design aligns with GDPR by limiting data collection, restricting access to authorized entities,
and providing only legally required information to authorities, thereby enhancing both security and privacy.